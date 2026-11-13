package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.audit.service.AuditService;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.security.FieldMaskingAutoConfiguration;
import io.saasforge.sdk.security.FieldMaskingPolicyProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/roles.sql")
@SuppressWarnings("unchecked")
class DataSecurityTest {
    static final String KEY = "target/test-masking-signing.jwk";
    static final String PLATFORM_KEY = "target/test-masking-platform-signing.jwk";
    static final String SERVICE_TOKEN = "test-masking-service-token";
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY);
        registry.add("forge.iam.platform.signing-key-file", () -> PLATFORM_KEY);
        registry.add("forge.iam.manifest.tokens.demo-service", () -> SERVICE_TOKEN);
        registry.add("forge.iam.manifest.tokens.other-service", () -> "test-masking-other-token");
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    AuditService audits;

    @Test
    void platformDefinesFourGradesAndL4CannotBeExported() throws Exception {
        var response = platform(HttpMethod.GET, "/grades", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var grades = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(grades).extracting(grade -> grade.get("grade")).containsExactly("L1", "L2", "L3", "L4");
        assertThat(grades.get(3)).containsEntry("name", "极敏感").containsEntry("exportAllowed", false);
    }

    @Test
    void platformMaintainsFixedRulesAndPreviewNeverEchoesFailedSample() throws Exception {
        var created = platform(
                HttpMethod.POST,
                "/rules",
                Map.of("code", "test-lifecycle", "name", "测试手机号", "rule", Map.of("strategy", "phone")));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(platform(
                                HttpMethod.POST,
                                "/rules",
                                Map.of("code", "test-lifecycle", "name", "重复", "rule", Map.of("strategy", "phone")))
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        var preview =
                platform(HttpMethod.POST, "/preview", Map.of("ruleCode", "test-lifecycle", "value", "13812345678"));
        assertThat(data(preview)).containsEntry("success", true).containsEntry("value", "138****5678");
        assertThat(platform(
                                HttpMethod.PUT,
                                "/rules/test-lifecycle",
                                Map.of("name", "姓名规则", "rule", Map.of("strategy", "keepFirst", "keepFirst", 99)))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var failed =
                platform(HttpMethod.POST, "/preview", Map.of("ruleCode", "test-lifecycle", "value", "private-sample"));
        assertThat(data(failed)).containsEntry("success", false).containsEntry("errorCode", "field-masking.unchanged");
        assertThat(failed.getBody().toString()).doesNotContain("private-sample");
        assertThat(platform(
                                HttpMethod.POST,
                                "/rules",
                                Map.of("code", "bad-rule", "name", "不接受正则", "rule", Map.of("strategy", "regex")))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(platform(HttpMethod.DELETE, "/rules/test-lifecycle", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat((List<Map<String, Object>>)
                        platform(HttpMethod.GET, "/rules", null).getBody().get("data"))
                .noneMatch(rule -> "test-lifecycle".equals(rule.get("code")));
    }

    @Test
    void tenantOverridesAreIsolatedAndInternalPoliciesBelongToAuthenticatedService() throws Exception {
        assertThat(report(
                                "field-flow",
                                List.of(
                                        Map.of("path", "person.phone", "grade", "L3", "defaultRule", "phone"),
                                        Map.of("path", "person.name", "grade", "L2"),
                                        Map.of("path", "person.secret", "grade", "L4")))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        assertThat(field(false, "person.phone"))
                .containsEntry("effectiveRule", "phone")
                .containsEntry("status", "active");
        assertThat(field(false, "person.secret")).containsEntry("exportAllowed", false);
        assertThat(data(tenant(
                        false,
                        false,
                        HttpMethod.PUT,
                        "/fields/demo-service/person.phone/rule",
                        Map.of("ruleCode", "name"))))
                .containsEntry("effectiveRule", "name");
        assertThat(field(true, "person.phone")).containsEntry("effectiveRule", "phone");
        var policies = data(call(
                SERVICE_TOKEN, HttpMethod.GET, "/internal/field-masking/019a0110-0000-7000-8000-000000000001", null));
        assertThat((Map<String, Object>) ((Map<String, Object>) policies.get("person.phone")).get("rule"))
                .containsEntry("strategy", "name");
        assertThat(data(call(
                        "test-masking-other-token",
                        HttpMethod.GET,
                        "/internal/field-masking/019a0110-0000-7000-8000-000000000001",
                        null)))
                .isEmpty();
        // 消费真实 IAM 的 HTTP 与 JSON 契约，避免服务端和 SDK 各自只对替身通过。
        new ApplicationContextRunner()
                .withUserConfiguration(FieldMaskingAutoConfiguration.class)
                .withPropertyValues(
                        "forge.field-masking.enabled=true",
                        "forge.field-masking.iam-uri=http://127.0.0.1:" + port,
                        "forge.field-masking.token=" + SERVICE_TOKEN)
                .run(context -> {
                    var provider = context.getBean(FieldMaskingPolicyProvider.class);
                    var effective = provider.policies(UUID.fromString("019a0110-0000-7000-8000-000000000001"));
                    assertThat(effective.get("person.phone").rule().strategy()).isEqualTo("name");
                    assertThat(effective.get("person.secret").exportAllowed()).isFalse();
                    var other = provider.policies(UUID.fromString("019a0110-0000-7000-8000-0000000000c1"));
                    assertThat(other.get("person.phone").rule().strategy()).isEqualTo("phone");
                });
        new ApplicationContextRunner()
                .withUserConfiguration(FieldMaskingAutoConfiguration.class)
                .withPropertyValues(
                        "forge.field-masking.enabled=true",
                        "forge.field-masking.iam-uri=http://127.0.0.1:" + port,
                        "forge.field-masking.token=test-masking-other-token")
                .run(context -> assertThat(context.getBean(FieldMaskingPolicyProvider.class)
                                .policies(UUID.fromString("019a0110-0000-7000-8000-000000000001")))
                        .isEmpty());
        assertThat(call(
                                "wrong-token",
                                HttpMethod.GET,
                                "/internal/field-masking/019a0110-0000-7000-8000-000000000001",
                                null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(tenant(false, false, HttpMethod.DELETE, "/fields/demo-service/person.phone/rule", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(field(false, "person.phone")).containsEntry("effectiveRule", "phone");
        assertThat(platform(HttpMethod.PUT, "/grades/L2", Map.of("defaultRule", "email"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(field(false, "person.name")).containsEntry("effectiveRule", "email");
        platform(HttpMethod.PUT, "/grades/L2", Map.of("defaultRule", "mask-middle"));
        assertThat(tenant(
                                false,
                                false,
                                HttpMethod.PUT,
                                "/fields/demo-service/missing.path/rule",
                                Map.of("ruleCode", "phone"))
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void referencesFromEveryTenantGradeAndCurrentManifestProtectRuleDeletion() throws Exception {
        platform(
                HttpMethod.POST,
                "/rules",
                Map.of("code", "cross-tenant-rule", "name", "跨租户引用", "rule", Map.of("strategy", "phone")));
        report("reference-flow", List.of(Map.of("path", "person.phone", "grade", "L3", "defaultRule", "phone")));
        assertThat(tenant(
                                true,
                                false,
                                HttpMethod.PUT,
                                "/fields/demo-service/person.phone/rule",
                                Map.of("ruleCode", "cross-tenant-rule"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(platform(HttpMethod.DELETE, "/rules/cross-tenant-rule", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        tenant(true, false, HttpMethod.DELETE, "/fields/demo-service/person.phone/rule", null);
        assertThat(platform(HttpMethod.DELETE, "/rules/cross-tenant-rule", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(platform(HttpMethod.DELETE, "/rules/replace", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        assertThat(platform(HttpMethod.DELETE, "/rules/phone", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        var invalid = report(
                "bad-reference", List.of(Map.of("path", "person.phone", "grade", "L3", "defaultRule", "missing-rule")));
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(field(false, "person.phone")).containsEntry("effectiveRule", "phone");
        assertThat(tenant(
                                false,
                                false,
                                HttpMethod.PUT,
                                "/fields/demo-service/person.phone/rule",
                                Map.of("ruleCode", "missing-rule"))
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void independentPermissionsRejectOrdinaryMembersAndWrongRealm() throws Exception {
        for (String uri : List.of("/fields", "/rules", "/grades")) {
            assertThat(tenant(false, true, HttpMethod.GET, uri, null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(403);
        }
        assertThat(tenant(false, true, HttpMethod.POST, "/preview", Map.of("ruleCode", "phone", "value", "13812345678"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(tenant(
                                false,
                                true,
                                HttpMethod.PUT,
                                "/fields/demo-service/person.phone/rule",
                                Map.of("ruleCode", "phone"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(token(false, false, false), HttpMethod.GET, "/platform/data-security/rules", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(call(token(true, false, false), HttpMethod.GET, "/data-security/rules", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    @Test
    void concurrentReferenceAndDeletionCannotLeaveBrokenTenantPolicy() throws Exception {
        report("concurrent-override", List.of(Map.of("path", "person.race", "grade", "L3", "defaultRule", "phone")));
        platform(
                HttpMethod.POST,
                "/rules",
                Map.of("code", "race-rule", "name", "并发引用", "rule", Map.of("strategy", "name")));
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var assign = pool.submit(() -> {
                start.await();
                return tenant(
                                true,
                                false,
                                HttpMethod.PUT,
                                "/fields/demo-service/person.race/rule",
                                Map.of("ruleCode", "race-rule"))
                        .getStatusCode()
                        .value();
            });
            var delete = pool.submit(() -> {
                start.await();
                return platform(HttpMethod.DELETE, "/rules/race-rule", null)
                        .getStatusCode()
                        .value();
            });
            start.countDown();
            int assigned = assign.get(10, TimeUnit.SECONDS);
            int deleted = delete.get(10, TimeUnit.SECONDS);
            assertThat(List.of(assigned, deleted)).isIn(List.of(200, 409), List.of(404, 200));
            assertThat(field(true, "person.race")).containsEntry("status", "active");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentManifestReferenceAndDeletionCannotPublishBrokenDefault() throws Exception {
        report("before-manifest-race", List.of());
        platform(
                HttpMethod.POST,
                "/rules",
                Map.of("code", "manifest-race-rule", "name", "并发清单引用", "rule", Map.of("strategy", "phone")));
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var reported = pool.submit(() -> {
                start.await();
                return report(
                                "manifest-race",
                                List.of(Map.of(
                                        "path", "race.phone", "grade", "L3", "defaultRule", "manifest-race-rule")))
                        .getStatusCode()
                        .value();
            });
            var deleted = pool.submit(() -> {
                start.await();
                return platform(HttpMethod.DELETE, "/rules/manifest-race-rule", null)
                        .getStatusCode()
                        .value();
            });
            start.countDown();
            int reportStatus = reported.get(10, TimeUnit.SECONDS);
            int deleteStatus = deleted.get(10, TimeUnit.SECONDS);
            assertThat(List.of(reportStatus, deleteStatus)).isIn(List.of(201, 409), List.of(400, 200));
            if (reportStatus == 201) {
                assertThat(field(false, "race.phone")).containsEntry("status", "active");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readOnlyGrantAllowsRegistryAndPreviewButCannotChangeConfiguration() throws Exception {
        var role = call(
                token(false, false, false),
                HttpMethod.POST,
                "/roles",
                Map.of(
                        "code",
                        "data-security-reader",
                        "name",
                        "数据安全只读",
                        "permissions",
                        List.of("iam:data-security:read")));
        String roleId = (String) data(role).get("id");
        String membership = "/roles/memberships/019a0110-0000-7000-8000-0000000000c5";
        call(token(false, false, false), HttpMethod.PUT, membership, Map.of("roleIds", List.of(roleId)));
        try {
            assertThat(tenant(false, true, HttpMethod.GET, "/fields", null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            assertThat(data(tenant(
                            false,
                            true,
                            HttpMethod.POST,
                            "/preview",
                            Map.of("ruleCode", "phone", "value", "13812345678"))))
                    .containsEntry("value", "138****5678");
            assertThat(tenant(
                                    false,
                                    true,
                                    HttpMethod.PUT,
                                    "/fields/demo-service/person.phone/rule",
                                    Map.of("ruleCode", "name"))
                            .getStatusCode()
                            .value())
                    .isEqualTo(403);
        } finally {
            call(token(false, false, false), HttpMethod.PUT, membership, Map.of("roleIds", List.of()));
        }
    }

    @Test
    void configurationAndFieldRegistrationAreAuditedWithoutPreviewOriginal() throws Exception {
        platform(
                HttpMethod.POST,
                "/rules",
                Map.of("code", "audit-rule", "name", "审计规则", "rule", Map.of("strategy", "phone")));
        report("audit-registration", List.of(Map.of("path", "audit.phone", "grade", "L3", "defaultRule", "phone")));
        tenant(false, false, HttpMethod.PUT, "/fields/demo-service/audit.phone/rule", Map.of("ruleCode", "audit-rule"));
        tenant(false, false, HttpMethod.POST, "/preview", Map.of("ruleCode", "audit-rule", "value", "13812345678"));
        // 与 AuditTest 相同，只替代 MQ 投递；验收从公开审计 HTTP 接口读取。
        outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .forEach(audits::store);
        var platformEvents = call(
                token(true, false, false),
                HttpMethod.GET,
                "/platform/audit/events?action=masking-rule.create&resourceId=audit-rule",
                null);
        assertThat((List<?>) platformEvents.getBody().get("data")).hasSize(1);
        var registration = call(
                token(true, false, false),
                HttpMethod.GET,
                "/platform/audit/events?action=sensitive-field.register&resourceId=demo-service",
                null);
        assertThat((List<?>) registration.getBody().get("data")).isNotEmpty();
        var tenantEvents = call(
                token(false, false, false),
                HttpMethod.GET,
                "/audit/events?action=field-masking.override&resourceId=demo-service:audit.phone",
                null);
        assertThat((List<?>) tenantEvents.getBody().get("data")).hasSize(1);
        assertThat(platformEvents.getBody().toString() + registration.getBody() + tenantEvents.getBody())
                .doesNotContain("13812345678");
        var otherTenantEvents = call(
                token(false, true, false),
                HttpMethod.GET,
                "/audit/events?action=field-masking.override&resourceId=demo-service:audit.phone",
                null);
        assertThat((List<?>) otherTenantEvents.getBody().get("data")).isEmpty();
    }

    Map<String, Object> field(boolean other, String path) throws Exception {
        return ((List<Map<String, Object>>) tenant(other, false, HttpMethod.GET, "/fields", null)
                        .getBody()
                        .get("data"))
                .stream()
                        .filter(field -> path.equals(field.get("path")))
                        .findFirst()
                        .orElseThrow();
    }

    ResponseEntity<Map> report(String version, List<?> fields) {
        return call(
                SERVICE_TOKEN,
                HttpMethod.POST,
                "/internal/manifests",
                Map.of(
                        "service",
                        "demo-service",
                        "version",
                        version,
                        "permissions",
                        List.of(),
                        "menus",
                        List.of(),
                        "sensitiveFields",
                        fields));
    }

    static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    ResponseEntity<Map> platform(HttpMethod method, String uri, Object body) throws Exception {
        return call(token(true, false, false), method, "/platform/data-security" + uri, body);
    }

    ResponseEntity<Map> tenant(boolean other, boolean member, HttpMethod method, String uri, Object body)
            throws Exception {
        return call(token(false, other, member), method, "/data-security" + uri, body);
    }

    ResponseEntity<Map> call(String token, HttpMethod method, String uri, Object body) {
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    static String token(boolean platform, boolean other, boolean member) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(platform ? PLATFORM_KEY : KEY)));
        var claims = new JWTClaimsSet.Builder()
                .issuer(platform ? "http://localhost:8080/iam/platform" : "http://localhost:8080/iam")
                .subject(
                        platform
                                ? "019a0110-0000-7000-8000-0000000000f1"
                                : other
                                        ? "019a0110-0000-7000-8000-0000000000c6"
                                        : member
                                                ? "019a0110-0000-7000-8000-0000000000c4"
                                                : "019a0110-0000-7000-8000-0000000000c2")
                .audience(platform ? "platform-console" : "tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("ver", 1);
        if (!platform) {
            claims.claim(
                            "tenant_id",
                            other ? "019a0110-0000-7000-8000-0000000000c1" : "019a0110-0000-7000-8000-000000000001")
                    .claim(
                            "membership_id",
                            other
                                    ? "019a0110-0000-7000-8000-0000000000c7"
                                    : member
                                            ? "019a0110-0000-7000-8000-0000000000c5"
                                            : "019a0110-0000-7000-8000-0000000000c3");
        }
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
