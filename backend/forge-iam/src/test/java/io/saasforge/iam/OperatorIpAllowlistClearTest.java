package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.ClientIpHeader;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
@Sql("/fixtures/operator_ip_allowlist.sql")
class OperatorIpAllowlistClearTest {
    static final UUID TENANT_A = UUID.fromString("019a0192-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0192-0000-7000-8000-000000000002");
    static final UUID IDENTITY = UUID.fromString("019a0110-0000-7000-8000-000000000002");
    static final UUID MEMBER_A = UUID.fromString("019a0192-0000-7000-8000-000000000011");
    static final UUID MEMBER_B = UUID.fromString("019a0192-0000-7000-8000-000000000012");
    static final UUID MANAGER = UUID.fromString("019a0192-0000-7000-8000-000000000041");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-operator-ip-allowlist-signing.jwk");
        registry.add(
                "forge.iam.platform.signing-key-file", () -> "target/test-operator-ip-allowlist-platform-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtEncoder tokens;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    OutboxAuditMapper outbox;

    @BeforeEach
    void restrictBothTenants() {
        assertThat(settings(HttpMethod.PUT, TENANT_A, MEMBER_A, Map.of("rules", List.of("198.51.100.0/24")))
                        .status())
                .isEqualTo(200);
        assertThat(settings(HttpMethod.PUT, TENANT_B, MEMBER_B, Map.of("rules", List.of("198.51.100.0/24")))
                        .status())
                .isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"019a0192-0000-7000-8000-000000000041", "019a0110-0000-7000-8000-0000000000f1"})
    void anOperatorClearsOnlyTheSpecifiedTenantAndPublishesUnrestrictedImmediately(String operator) {
        Response cleared = clear(platformToken(UUID.fromString(operator)), TENANT_A, Map.of("reason", "恢复租户访问"));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.code()).isEqualTo("ok");
        assertThat(cleared.body().get("data")).isNull();
        assertThat(settings(HttpMethod.GET, TENANT_A, MEMBER_A, null).data()).containsEntry("rules", List.of());
        assertThat(redisTemplate.opsForValue().get("iam:" + TENANT_A + ":ip-allowlist"))
                .isEqualTo("*");
        assertThat(settings(HttpMethod.GET, TENANT_B, MEMBER_B, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
        assertThat(redisTemplate.opsForValue().get("iam:" + TENANT_B + ":ip-allowlist"))
                .isEqualTo("198.51.100.0/24");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "019a0192-0000-7000-8000-000000000042",
                "019a0192-0000-7000-8000-000000000043",
                "019a0192-0000-7000-8000-000000000099"
            })
    void onlyAnActiveOperatorWithManageTenantPermissionCanClear(String actor) {
        Response denied = clear(platformToken(UUID.fromString(actor)), TENANT_A, Map.of("reason", "恢复租户访问"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("auth.forbidden");
        assertThat(settings(HttpMethod.GET, TENANT_A, MEMBER_A, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
        assertThat(redisTemplate.opsForValue().get("iam:" + TENANT_A + ":ip-allowlist"))
                .isEqualTo("198.51.100.0/24");
    }

    @Test
    void tenantTokensAndUnauthenticatedRequestsCannotUseTheEmergencyEndpoint() {
        String tenantToken = token("http://localhost:8080/iam", "tenant-app", IDENTITY, TENANT_A, MEMBER_A);
        for (String bearer : new String[] {tenantToken, null}) {
            Response denied = clear(bearer, TENANT_A, Map.of("reason", "恢复租户访问"));
            assertThat(denied.status()).isEqualTo(401);
            assertThat(denied.code()).isEqualTo("auth.unauthenticated");
        }
        assertThat(settings(HttpMethod.GET, TENANT_A, MEMBER_A, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
    }

    @Test
    void aReasonIsRequiredAndCannotExceedFiveHundredCharacters() {
        List<Object> invalid = List.of(
                Map.of(),
                Collections.singletonMap("reason", null),
                Map.of("reason", ""),
                Map.of("reason", " \t\n "),
                Map.of("reason", "\u2003"),
                Map.of("reason", "a".repeat(501)));
        for (Object body : invalid) {
            Response rejected = clear(platformToken(MANAGER), TENANT_A, body);
            assertThat(rejected.status()).as(body.toString()).isEqualTo(400);
            assertThat(rejected.code()).isEqualTo("request.invalid");
            assertThat((List<?>) rejected.body().get("errors"))
                    .anySatisfy(error ->
                            assertThat(((Map<?, ?>) error).get("field")).isEqualTo("reason"));
        }
        assertThat(settings(HttpMethod.GET, TENANT_A, MEMBER_A, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
        assertThat(clear(platformToken(MANAGER), TENANT_A, Map.of("reason", "a".repeat(500)))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void missingAndDeletedTenantsReturnNotFoundWithoutChangingAnotherTenant() {
        for (UUID target : List.of(
                UUID.fromString("019a0192-0000-7000-8000-000000000003"),
                UUID.fromString("019a0192-0000-7000-8000-000000000099"))) {
            Response rejected = clear(platformToken(MANAGER), target, Map.of("reason", "恢复租户访问"));
            assertThat(rejected.status()).isEqualTo(404);
            assertThat(rejected.code()).isEqualTo("tenant.ipAllowlistNotFound");
            assertThat(rejected.body().get("userTip")).isEqualTo("租户不存在或已被删除");
            assertThat(redisTemplate.opsForValue().get("iam:" + target + ":ip-allowlist"))
                    .isNull();
        }
        assertThat(settings(HttpMethod.GET, TENANT_A, MEMBER_A, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
    }

    @Test
    void aSuccessfulClearRecordsTheReasonTargetAndOperatorInThePlatformAudit() {
        int before = clears().size();
        Response cleared = clear(platformToken(MANAGER), TENANT_A, Map.of("reason", "  恢复租户访问  "));
        assertThat(cleared.status()).isEqualTo(200);
        List<AuditEvent> events = clears();
        assertThat(events).hasSize(before + 1);
        AuditEvent event = events.get(events.size() - 1);
        assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
        assertThat(event.category()).isEqualTo(AuditCategory.OPERATION);
        assertThat(event.tenantId()).isNull();
        assertThat(event.actorId()).isEqualTo(MANAGER);
        assertThat(event.resourceType()).isEqualTo("tenant");
        assertThat(event.resourceId()).isEqualTo(TENANT_A.toString());
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.detail()).containsExactlyEntriesOf(Map.of("reason", "恢复租户访问"));
        assertThat(event.service()).isEqualTo("forge-iam");
    }

    @Test
    void aRedisPublishFailureRollsBackTheClearAndDoesNotRecordSuccess() throws Exception {
        int before = clears().size();
        // Redis 是真实系统边界：只让本次测试的临时实例拒写，GET 仍可验证旧发布值。
        redis.container().execInContainer("redis-cli", "CONFIG", "SET", "min-replicas-to-write", "1");
        try {
            Response failed = clear(platformToken(MANAGER), TENANT_A, Map.of("reason", "恢复租户访问"));
            assertThat(failed.status()).isBetween(500, 599);
        } finally {
            redis.container().execInContainer("redis-cli", "CONFIG", "SET", "min-replicas-to-write", "0");
        }
        assertThat(settings(HttpMethod.GET, TENANT_A, MEMBER_A, null).data())
                .containsEntry("rules", List.of("198.51.100.0/24"));
        assertThat(redisTemplate.opsForValue().get("iam:" + TENANT_A + ":ip-allowlist"))
                .isEqualTo("198.51.100.0/24");
        assertThat(clears()).hasSize(before);
    }

    private List<AuditEvent> clears() {
        // 此测试不连接 MQ，待投递消息体是 HTTP 产生的审计事件契约。
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> "tenant.ipAllowlistClear".equals(event.action()))
                .toList();
    }

    private Response clear(String bearer, UUID tenant, Object body) {
        return call(
                HttpMethod.POST, "/platform/tenants/" + tenant + "/ip-allowlist/clear", bearer, body, "203.0.113.9");
    }

    private Response settings(HttpMethod method, UUID tenant, UUID membership, Object body) {
        String bearer = token("http://localhost:8080/iam", "tenant-app", IDENTITY, tenant, membership);
        return call(method, "/security-settings/ip-allowlist", bearer, body, "198.51.100.5");
    }

    private Response call(HttpMethod method, String path, String bearer, Object body, String ip) {
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(path)
                .headers(headers -> {
                    if (bearer != null) headers.setBearerAuth(bearer);
                    headers.set(ClientIpHeader.NAME, ip);
                });
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    private String platformToken(UUID operator) {
        return token("http://localhost:8080/iam/platform", "platform-console", operator, null, null);
    }

    private String token(String issuer, String audience, UUID actor, UUID tenant, UUID membership) {
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(actor.toString())
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("ver", 0);
        if (tenant != null) claims.claim("tenant_id", tenant.toString());
        if (membership != null) claims.claim("membership_id", membership.toString());
        return tokens.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
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
