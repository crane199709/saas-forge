package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.ClientIpHeader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/ip_enforcement.sql")
class TenantIpEnforcementTest {
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final UUID TENANT_A = UUID.fromString("019a0191-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0191-0000-7000-8000-000000000002");
    static final UUID MEMBER_A = UUID.fromString("019a0191-0000-7000-8000-000000000011");
    static final UUID MEMBER_B = UUID.fromString("019a0191-0000-7000-8000-000000000012");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-ip-enforcement-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Test
    void globalLoginSucceedsButExchangeFromAnIpOutsideTheTargetTenantIsForbidden() {
        String cookie = login();
        Response denied = exchange(cookie, MEMBER_A, "198.51.100.6");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body()).containsEntry("code", "tenant.ipNotAllowed");
        assertThat(denied.body()).containsEntry("userTip", "当前 IP 不在租户允许列表内");
    }

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void rejectedGrantsAreAuditedForTheTargetTenantAndDoNotConsumeTheRefreshCredential() {
        int before = denials().size();
        String cookie = login();
        assertDenied(exchange(cookie, MEMBER_A, "198.51.100.6"));
        Response entered = exchange(cookie, MEMBER_A, "198.51.100.5");
        assertThat(entered.status()).isEqualTo(200);
        String refresh = (String) entered.data().get("refreshToken");
        assertDenied(post("/auth/refresh", Map.of("refreshToken", refresh), null, "198.51.100.6"));
        Response otherTenant =
                post("/auth/switch", Map.of("refreshToken", refresh, "membershipId", MEMBER_B), null, "198.51.100.6");
        assertThat(otherTenant.status()).isEqualTo(200);
        assertThat(otherTenant.data()).containsEntry("tenantId", TENANT_B.toString());
        String otherRefresh = (String) otherTenant.data().get("refreshToken");
        assertDenied(post(
                "/auth/switch", Map.of("refreshToken", otherRefresh, "membershipId", MEMBER_A), null, "198.51.100.6"));
        assertThat(post("/auth/refresh", Map.of("refreshToken", otherRefresh), null, "198.51.100.6")
                        .status())
                .isEqualTo(200);
        assertThat(denials()).hasSize(before + 3).allSatisfy(event -> {
            assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
            assertThat(event.errorCode()).isEqualTo("tenant.ipNotAllowed");
            assertThat(event.actorId()).isEqualTo(UUID.fromString("019a0110-0000-7000-8000-000000000002"));
            assertThat(event.resourceId()).isEqualTo(MEMBER_A.toString());
        });
    }

    @Test
    void aBlockedTenantCanReadGlobalMembershipsWithoutRotatingCredentialsAndSwitchToAnotherTenant() {
        Response entered = exchange(login(), MEMBER_A, "198.51.100.5");
        assertThat(entered.status()).isEqualTo(200);
        String refresh = (String) entered.data().get("refreshToken");
        assertDenied(post("/auth/refresh", Map.of("refreshToken", refresh), null, "198.51.100.6"));
        Response memberships = post("/auth/memberships", Map.of("refreshToken", refresh), null, "198.51.100.6");
        assertThat(memberships.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed =
                (List<Map<String, Object>>) memberships.body().get("data");
        assertThat(listed)
                .extracting(item -> item.get("membershipId"))
                .contains(MEMBER_A.toString(), MEMBER_B.toString());
        assertThat(post("/auth/memberships", Map.of("refreshToken", refresh), null, "198.51.100.6")
                        .status())
                .isEqualTo(200);
        Response switched =
                post("/auth/switch", Map.of("refreshToken", refresh, "membershipId", MEMBER_B), null, "198.51.100.6");
        assertThat(switched.status()).isEqualTo(200);
        assertThat(switched.data()).containsEntry("tenantId", TENANT_B.toString());
    }

    @Test
    void membershipReadsRejectWrongTokenTypesAndOldCredentialsWithoutRevokingTheCurrentAuthorization() {
        Response entered = exchange(login(), MEMBER_B, "198.51.100.6");
        String original = (String) entered.data().get("refreshToken");
        Response rotated = post("/auth/refresh", Map.of("refreshToken", original), null, "198.51.100.6");
        assertThat(rotated.status()).isEqualTo(200);
        for (String invalid : List.of("invalid", original, authorizationCode(login()))) {
            Response denied = post("/auth/memberships", Map.of("refreshToken", invalid), null, "198.51.100.6");
            assertThat(denied.status()).isEqualTo(401);
            assertThat(denied.body()).containsEntry("code", "auth.failed");
        }
        // Access Token 超过刷新凭证模型上限，在入参校验阶段直接拒绝。
        assertThat(post(
                                "/auth/memberships",
                                Map.of("refreshToken", rotated.data().get("accessToken")),
                                null,
                                "198.51.100.6")
                        .status())
                .isEqualTo(400);
        String current = (String) rotated.data().get("refreshToken");
        assertThat(post("/auth/memberships", Map.of("refreshToken", current), null, "198.51.100.6")
                        .status())
                .isEqualTo(200);
        Response refreshed = post("/auth/refresh", Map.of("refreshToken", current), null, "198.51.100.6");
        assertThat(refreshed.status()).isEqualTo(200);
        // 只读查询不撤销；真正刷新时重放旧凭证依旧撤销整条授权链。
        assertThat(post("/auth/refresh", Map.of("refreshToken", original), null, "198.51.100.6")
                        .status())
                .isEqualTo(401);
        assertThat(post(
                                "/auth/memberships",
                                Map.of("refreshToken", refreshed.data().get("refreshToken")),
                                null,
                                "198.51.100.6")
                        .status())
                .isEqualTo(401);
    }

    @Test
    void platformRefreshCredentialsCannotReadTenantMemberships() {
        Response login = post(
                "/platform/auth/login",
                Map.of("account", "operator", "password", "Operator-password-123!"),
                null,
                "198.51.100.6");
        assertThat(login.status()).isEqualTo(200);
        String code = authorizationCode(login.cookie(), true);
        Response operator =
                post("/platform/auth/exchange", Map.of("code", code, "codeVerifier", VERIFIER), null, "198.51.100.6");
        assertThat(operator.status()).isEqualTo(200);
        Response denied = post(
                "/auth/memberships", Map.of("refreshToken", operator.data().get("refreshToken")), null, "198.51.100.6");
        assertThat(denied.status()).isEqualTo(401);
        assertThat(denied.body()).containsEntry("code", "auth.failed");
        assertThat(post(
                                "/platform/auth/refresh",
                                Map.of("refreshToken", operator.data().get("refreshToken")),
                                null,
                                "198.51.100.6")
                        .status())
                .isEqualTo(200);
    }

    @Test
    @Sql("/fixtures/multiple_memberships.sql")
    void membershipRecoveryOnlyListsTheCredentialIdentitysActiveMemberships() {
        Response entered =
                exchange(login("multi"), UUID.fromString("019a0110-0000-7000-8000-000000000006"), "198.51.100.6");
        assertThat(entered.status()).isEqualTo(200);
        String refresh = (String) entered.data().get("refreshToken");
        Response response = post(
                "/auth/memberships",
                Map.of("refreshToken", refresh, "identityId", "019a0110-0000-7000-8000-000000000002"),
                null,
                "198.51.100.6");
        assertThat(response.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed =
                (List<Map<String, Object>>) response.body().get("data");
        assertThat(listed)
                .extracting(item -> item.get("membershipId"))
                .containsExactlyInAnyOrder(
                        "019a0110-0000-7000-8000-000000000006", "019a0110-0000-7000-8000-000000000007");
        Response foreign =
                post("/auth/switch", Map.of("refreshToken", refresh, "membershipId", MEMBER_A), null, "198.51.100.6");
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.body()).containsEntry("code", "auth.tenantAccessDenied");
        assertThat(post("/auth/memberships", Map.of("refreshToken", refresh), null, "198.51.100.6")
                        .status())
                .isEqualTo(200);
    }

    private static void assertDenied(Response response) {
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).containsEntry("code", "tenant.ipNotAllowed");
    }

    private List<AuditEvent> denials() {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> "tenant.ipAllowlistDenied".equals(event.action()) && TENANT_A.equals(event.tenantId()))
                .toList();
    }

    private String login() {
        return login("demo");
    }

    private String login(String account) {
        Response response =
                post("/auth/login", Map.of("account", account, "password", "Demo-password-123!"), null, "198.51.100.6");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data()).containsEntry("nextStep", "authorize");
        return response.cookie();
    }

    private Response exchange(String cookie, UUID membership, String ip) {
        return post(
                "/auth/exchange",
                Map.of("code", authorizationCode(cookie), "codeVerifier", VERIFIER, "membershipId", membership),
                null,
                ip);
    }

    private Response post(String path, Object body, String cookie, String ip) {
        return client().post()
                .uri(path)
                .headers(headers -> {
                    if (cookie != null) headers.set("Cookie", cookie);
                    if (ip != null) headers.set(ClientIpHeader.NAME, ip);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    String issued = res.getHeaders().getFirst("Set-Cookie");
                    return new Response(
                            res.getStatusCode().value(),
                            res.bodyTo(Map.class),
                            issued == null ? cookie : issued.split(";")[0]);
                });
    }

    record Response(int status, Map<String, Object> body, String cookie) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }

    private String authorizationCode(String cookie) {
        return authorizationCode(cookie, false);
    }

    private String authorizationCode(String cookie, boolean platform) {
        var response = authorizeRequest(cookie, platform).retrieve().toBodilessEntity();
        return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private RestClient.RequestHeadersSpec<?> authorizeRequest(String cookie, boolean platform) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            return client().get()
                    .uri(builder -> builder.path("/oauth2/authorize")
                            .queryParam("response_type", "code")
                            .queryParam("client_id", platform ? "platform-console" : "tenant-app")
                            .queryParam("redirect_uri", platform ? "http://localhost:5173/" : "http://localhost:5174/")
                            .queryParam("scope", platform ? "platform" : "tenant")
                            .queryParam("state", "test-state")
                            .queryParam("code_challenge_method", "S256")
                            .queryParam("code_challenge", challenge)
                            .build())
                    .header("Cookie", cookie);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }
}
