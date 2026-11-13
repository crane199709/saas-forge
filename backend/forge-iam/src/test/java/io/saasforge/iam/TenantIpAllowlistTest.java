package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.ClientIpHeader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/ip_allowlist.sql")
class TenantIpAllowlistTest {
    static final UUID TENANT_A = UUID.fromString("019a0190-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0190-0000-7000-8000-000000000002");
    static final UUID IDENTITY = UUID.fromString("019a0110-0000-7000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("019a0190-0000-7000-8000-000000000011");
    static final UUID MEMBER_B = UUID.fromString("019a0190-0000-7000-8000-000000000012");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add(
                "spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/demo,classpath:db/test-ip-allowlist");
        registry.add("forge.iam.signing-key-file", () -> "target/test-ip-allowlist-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtEncoder tokens;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void startupRestoresRestrictedAndUnrestrictedTenantsFromTheDatabase() {
        assertThat(redisTemplate.opsForValue().get("iam:019a0190-0000-7000-8000-000000000003:ip-allowlist"))
                .isEqualTo("2001:db8::/32");
        assertThat(redisTemplate.opsForValue().get("iam:019a0190-0000-7000-8000-000000000004:ip-allowlist"))
                .isEqualTo("*");
        assertThat(redisTemplate.opsForValue().get("iam:019a0110-0000-7000-8000-000000000001:ip-allowlist"))
                .isEqualTo("*");
    }

    @Test
    void tenantAdminSavesNormalizedRulesAndReadsThemWithTheCurrentIp() {
        Response updated = settings(
                HttpMethod.PUT,
                TENANT_A,
                ADMIN,
                Map.of("rules", List.of(" 192.0.2.129/24 ", "2001:0DB8:0000::12/64", "198.51.100.5", "2001:db8::1")));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data())
                .containsEntry("rules", List.of("192.0.2.0/24", "2001:db8::/64", "198.51.100.5", "2001:db8::1"));
        assertThat(settings(HttpMethod.GET, TENANT_A, ADMIN, null).data()).isEqualTo(updated.data());
        assertThat(updated.data()).containsEntry("currentIp", "198.51.100.5");
    }

    @Test
    void aRuleUpdateCannotLockOutTheCurrentIpAndLeavesThePreviousRulesUsable() {
        assertThat(settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of("198.51.100.0/24")))
                        .status())
                .isEqualTo(200);
        Response denied = settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of("192.0.2.0/24")));
        assertThat(denied.status()).isEqualTo(400);
        assertThat(denied.code()).isEqualTo("tenant.currentIpNotIncluded");
        assertThat(settings(HttpMethod.GET, TENANT_A, ADMIN, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
        assertThat(settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of()))
                        .status())
                .isEqualTo(200);
        assertThat(settings(HttpMethod.GET, TENANT_A, ADMIN, null).data()).containsEntry("rules", List.of());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "256.1.2.3",
                "192.0.2",
                "192.0.2.1/0",
                "::/0",
                "192.0.2.1/33",
                "::1/129",
                "fe80::1%eth0",
                "[::1]",
                "2001:::1",
                "1:2:3:4:5:6:7:8:9",
                "192.0.2.1/-1",
                "192.0.2.1/24/2",
                ""
            })
    void invalidRulesReportTheirListIndex(String invalid) {
        Response response =
                settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of("198.51.100.5", invalid)));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("request.invalid");
        assertThat((List<?>) response.body().get("errors"))
                .anySatisfy(
                        error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo("rules[1]"));
    }

    @Test
    void theRuleLimitIsOneHundred() {
        assertThat(settings(
                                HttpMethod.PUT,
                                TENANT_A,
                                ADMIN,
                                Map.of("rules", java.util.Collections.nCopies(100, "198.51.100.5")))
                        .status())
                .isEqualTo(200);
        Response tooMany = settings(
                HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", java.util.Collections.nCopies(101, "198.51.100.5")));
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.code()).isEqualTo("request.invalid");
    }

    @Test
    void successfulUpdatesAreAuditedAndRedisWriteFailurePreservesThePreviousRules() throws Exception {
        long before = updates().size();
        assertThat(settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of("198.51.100.5")))
                        .status())
                .isEqualTo(200);
        List<AuditEvent> updated = updates();
        assertThat(updated).hasSize((int) before + 1);
        AuditEvent event = updated.get(updated.size() - 1);
        assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
        assertThat(event.actorId()).isEqualTo(IDENTITY);
        assertThat(event.resourceId()).isEqualTo(TENANT_A.toString());
        assertThat(event.detail()).containsEntry("rules", List.of("198.51.100.5"));
        assertThat(redisTemplate.opsForValue().get("iam:" + TENANT_A + ":ip-allowlist"))
                .isEqualTo("198.51.100.5");

        // 只让本测试的临时 Redis 拒绝写入，GET 仍可工作；不伪造服务内部协作者。
        redis.container().execInContainer("redis-cli", "CONFIG", "SET", "min-replicas-to-write", "1");
        try {
            Response failed = settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of("198.51.100.0/24")));
            assertThat(failed.status()).isBetween(500, 599);
        } finally {
            redis.container().execInContainer("redis-cli", "CONFIG", "SET", "min-replicas-to-write", "0");
        }
        assertThat(settings(HttpMethod.GET, TENANT_A, ADMIN, null).data())
                .containsEntry("rules", List.of("198.51.100.5"));
        assertThat(redisTemplate.opsForValue().get("iam:" + TENANT_A + ":ip-allowlist"))
                .isEqualTo("198.51.100.5");
        assertThat(updates()).hasSize(updated.size());
    }

    @Test
    void managementPermissionInOneTenantDoesNotPermitReadingOrChangingAnotherTenant() {
        assertThat(settings(HttpMethod.GET, TENANT_A, ADMIN, null).status()).isEqualTo(200);
        assertThat(settings(HttpMethod.GET, TENANT_B, MEMBER_B, null).status()).isEqualTo(403);
        Response denied = settings(HttpMethod.PUT, TENANT_B, MEMBER_B, Map.of("rules", List.of()));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("auth.forbidden");
        int unauthenticated = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/security-settings/ip-allowlist")
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(unauthenticated).isEqualTo(401);
    }

    @ParameterizedTest
    @CsvSource({
        "198.51.100.4/31,198.51.100.5,198.51.100.6",
        "2001:db8::/126,2001:db8::3,2001:db8::4",
        "2001:db8::1,2001:db8::1,2001:db8::2",
        "::ffff:192.0.2.1,::ffff:192.0.2.1,192.0.2.1"
    })
    void currentIpMustMatchTheAddressFamilyAndExactCidrBoundary(String rule, String inside, String outside) {
        assertThat(settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of(rule)), inside)
                        .status())
                .isEqualTo(200);
        Response denied = settings(HttpMethod.PUT, TENANT_A, ADMIN, Map.of("rules", List.of(rule)), outside);
        assertThat(denied.status()).isEqualTo(400);
        assertThat(denied.code()).isEqualTo("tenant.currentIpNotIncluded");
    }

    private List<AuditEvent> updates() {
        // 测试不连接 MQ；读取待投递消息体，验证 HTTP 操作产出的审计事件契约。
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> "tenant.ipAllowlistUpdate".equals(event.action()) && TENANT_A.equals(event.tenantId()))
                .toList();
    }

    private Response settings(HttpMethod method, UUID tenant, UUID membership, Object body) {
        return settings(method, tenant, membership, body, "198.51.100.5");
    }

    private Response settings(HttpMethod method, UUID tenant, UUID membership, Object body, String ip) {
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri("/security-settings/ip-allowlist")
                .headers(headers -> {
                    headers.setBearerAuth(token(tenant, membership));
                    headers.set(ClientIpHeader.NAME, ip);
                });
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    private String token(UUID tenant, UUID membership) {
        return tokens.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                        .issuer("http://localhost:8080/iam")
                        .subject(IDENTITY.toString())
                        .audience(List.of("tenant-app"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .claim("tenant_id", tenant.toString())
                        .claim("membership_id", membership.toString())
                        .claim("ver", 1)
                        .build()))
                .getTokenValue();
    }

    record Response(int status, Map<?, ?> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }

        String code() {
            return (String) body.get("code");
        }
    }
}
