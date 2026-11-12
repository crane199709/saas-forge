package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.demo.masking.model.MaskedProfile;
import io.saasforge.demo.masking.service.MaskingDemoService;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.core.api.ApiResponse;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 在线脱敏以真实 HTTP 为接缝，经过权限、Manifest 登记与 IAM 客户端。 */
@ExtendWith(OutputCaptureExtension.class)
@Import(FieldMaskingHttpTest.LargeResponseController.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FieldMaskingHttpTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        TestTokens.register(registry);
        TestMasking.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void nestedCollectionsAreMaskedAndUndeclaredFieldsStayUnchanged() throws Exception {
        UUID tenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID, List.of("masking:read"));
        RestClient.create("http://localhost:" + port)
                .get()
                .uri("/masking/profiles")
                .header("Authorization", "Bearer " + TestTokens.forTenant(tenant))
                .exchange((req, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    String body =
                            new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    var data = JSON.readTree(body).path("data");
                    assertThat(data.path("profiles").get(0).path("phone").asString())
                            .isEqualTo("138****5678");
                    assertThat(data.path("profiles").get(0).path("idCard").asString())
                            .isEqualTo("110101********1234");
                    assertThat(data.path("profiles").get(0).path("note").asString())
                            .isEqualTo("公开说明");
                    assertThat(body).doesNotContain("13812345678", "110101199001011234");
                    return null;
                });
    }

    @Test
    void aLateSensitiveFailureReturnsOneComplete500Envelope(CapturedOutput logs) throws Exception {
        UUID tenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID, List.of("masking:read"));
        String good = get(tenant, "/masking-test/large", 200);
        assertThat(good.length()).isGreaterThan(64000);
        assertThat(JSON.readTree(good).path("data").path("profiles").size()).isEqualTo(2);
        for (String mode : List.of("missingField", "missingRule", "engine", "unavailable")) {
            TestMasking.fail(tenant, mode);
            String body = get(tenant, "/masking-test/large", 500);
            var envelope = JSON.readTree(body);
            assertThat(envelope.path("httpStatus").asInt()).isEqualTo(500);
            assertThat(envelope.path("code").asString()).isEqualTo("system.error");
            assertThat(envelope.has("data")).isFalse();
            assertThat(body).doesNotContain("13812345678", "110101199001011234", "padding", "profiles");
        }
        assertThat(logs).doesNotContain("13812345678", "110101199001011234");
    }

    @Test
    void nullSensitiveValuesStillValidateTheDeclaredRules() throws Exception {
        UUID tenant = UUID.randomUUID();
        String valid = get(tenant, "/masking-test/null", 200);
        assertThat(JSON.readTree(valid).path("data").path("phone").isNull()).isTrue();
        TestMasking.fail(tenant, "missingRule");
        assertThat(JSON.readTree(get(tenant, "/masking-test/null", 500)).has("data"))
                .isFalse();
    }

    @Test
    void aResponseUsesOneSnapshotEvenIfIamChangesBetweenLookups() throws Exception {
        UUID tenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID, List.of("masking:read"));
        TestMasking.changeAfterFirstLookup(tenant);
        var first = JSON.readTree(get(tenant, "/masking-test/large", 200))
                .path("data")
                .path("profiles");
        assertThat(first).allSatisfy(profile -> {
            assertThat(profile.path("phone").asString()).isEqualTo("138****5678");
            assertThat(profile.path("idCard").asString()).isEqualTo("110101********1234");
        });
        var next = JSON.readTree(get(tenant, "/masking/profiles", 200))
                .path("data")
                .path("profiles");
        assertThat(next.get(0).path("phone").asString()).isEqualTo("[changed]");
    }

    @Test
    void rawViewNeedsItsOwnPermissionAndAuditsBothOutcomesWithoutOriginalValues() throws Exception {
        UUID tenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID, List.of("masking:read"));
        assertThat(JSON.readTree(get(tenant, "/masking/profiles/raw", 403))
                        .path("code")
                        .asString())
                .isEqualTo("auth.forbidden");
        UUID authorized = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, authorized, List.of("masking:read", "masking:viewRaw"));
        var raw = JSON.readTree(get(tenant, "/masking/profiles/raw", 200, authorized))
                .path("data");
        assertThat(raw.path("phone").asString()).isEqualTo("13812345678");
        assertThat(raw.path("idCard").asString()).isEqualTo("110101199001011234");
        assertThat(get(tenant, "/masking/profiles", 200, authorized))
                .contains("138****5678")
                .doesNotContain("13812345678");
        var payloads = outbox.payloads(tenant, "AuditEvent");
        assertThat(payloads)
                .allSatisfy(payload -> assertThat(payload).doesNotContain("13812345678", "110101199001011234"));
        var events = payloads.stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .toList();
        assertThat(events).extracting(AuditEvent::outcome).containsExactly(AuditOutcome.FAILURE, AuditOutcome.SUCCESS);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.action()).isEqualTo("profile.viewRaw");
            assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.resourceId()).isEqualTo("demo");
        });
    }

    @Test
    void realLogbackEncoderUsesTheSameRulesAsTheOnlineResponse() throws Exception {
        UUID tenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID, List.of("masking:read"));
        var logger = (Logger) LoggerFactory.getLogger(MaskingDemoService.class);
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var output = new ByteArrayOutputStream();
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%message%n");
        encoder.start();
        var appender = new OutputStreamAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(output);
        appender.start();
        logger.addAppender(appender);
        try {
            for (int attempt = 0; attempt < 50; attempt++) {
                RestClient.create("http://localhost:" + port)
                        .post()
                        .uri("/masking/profiles/log")
                        .header("Authorization", "Bearer " + TestTokens.forTenant(tenant))
                        .exchange((req, response) -> {
                            assertThat(response.getStatusCode().value()).isEqualTo(200);
                            return null;
                        });
                String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(text).doesNotContain("13812345678", "110101199001011234");
                if (text.contains("138****5678") && text.contains("110101********1234")) break;
                Thread.sleep(100);
            }
            assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("138****5678", "110101********1234", "公开说明")
                    .doesNotContain("13812345678", "110101199001011234");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            encoder.stop();
        }
    }

    private String get(UUID tenant, String path, int status) throws Exception {
        return get(tenant, path, status, TestTokens.MEMBERSHIP_ID);
    }

    private String get(UUID tenant, String path, int status, UUID member) throws Exception {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Authorization", "Bearer " + TestTokens.forMembership(tenant, member))
                .exchange((req, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(status);
                    return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                });
    }

    /** 大响应只用于测试；前缀超过 Servlet 缓冲，最后的身份证字段故意由 IAM 返回无效规则。 */
    @RestController
    static class LargeResponseController {
        private final MaskingDemoService service;

        LargeResponseController(MaskingDemoService service) {
            this.service = service;
        }

        @GetMapping("/masking-test/null")
        public ApiResponse<MaskedProfile> nullValues() {
            return ApiResponse.ok(new MaskedProfile(null, null, "公开说明"));
        }

        @GetMapping("/masking-test/large")
        public ApiResponse<LargeResult> large() {
            var profile = service.profiles().get(0);
            return ApiResponse.ok(new LargeResult("x".repeat(64000), List.of(profile, profile)));
        }
    }

    record LargeResult(String padding, List<MaskedProfile> profiles) {}
}
