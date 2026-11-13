package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.mfa.service.MfaResetService;
import io.saasforge.iam.mfa.service.Totp;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant 的 MFA Requirement（账号安全工单 03）：Tenant Admin 开关要求；签发进入有要求的 Tenant 的 Token 时（exchange、switch、refresh），
 * 授权未完成 MFA 即拒绝；被拦的身份当场绑定后，该会话与当前授权视为已完成 MFA，可重试进入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/mfa_requirement.sql")
class TenantMfaRequirementTest {
    static final String PASSWORD = "Demo-password-123!";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final UUID SETTINGS_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000001");
    static final UUID REQUIRING_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000002");
    static final UUID FREE_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000003");
    static final UUID SECADMIN = UUID.fromString("019a0130-0000-7000-8000-000000000011");
    static final UUID SWITCH_IN_FREE_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000024");
    static final UUID SWITCH_IN_REQUIRING_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000034");
    static final UUID UNBIND_IN_FREE_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000027");
    static final UUID UNBIND_IN_REQUIRING_TENANT = UUID.fromString("019a0130-0000-7000-8000-000000000037");
    static final UUID RESET_IDENTITY = UUID.fromString("019a0130-0000-7000-8000-00000000001a");
    static final UUID RESET_IN_FREE_TENANT = UUID.fromString("019a0130-0000-7000-8000-00000000002a");
    static final UUID RESET_IN_REQUIRING_TENANT = UUID.fromString("019a0130-0000-7000-8000-00000000003a");
    static final UUID SUPER_ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-mfa-requirement-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    MfaResetService mfaReset;

    @Test
    void anAdminEnablesTheRequirementAndUnboundMembersAreStoppedAtTheirNextRefresh() {
        String admin = (String) enter("secadmin", null).get("accessToken");
        Map<?, ?> member = enter("secmember", null);

        // 默认关闭；Plan 中没有任何权限码也能管理（iam 管理权限码不受套餐限制）。
        Response initial = settings(admin, HttpMethod.GET, null);
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.data()).containsEntry("mfaRequired", false);

        Response enabled = settings(admin, HttpMethod.PUT, Map.of("mfaRequired", true));
        assertThat(enabled.status()).isEqualTo(200);
        assertThat(enabled.data()).containsEntry("mfaRequired", true);
        assertThat(settings(admin, HttpMethod.GET, null).data()).containsEntry("mfaRequired", true);

        // 不即时吊销：已签发的 Access Token 仍可用，下一次刷新被拦。
        assertThat(settings((String) member.get("accessToken"), HttpMethod.GET, null)
                        .status())
                .isEqualTo(403);
        Response refreshed = post("/auth/refresh", Map.of("refreshToken", member.get("refreshToken")), null);
        assertThat(refreshed.status()).isEqualTo(403);
        assertThat(refreshed.code()).isEqualTo("auth.mfaRequired");

        List<AuditEvent> audits = outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("tenantSecurity.update"))
                .filter(event -> SETTINGS_TENANT.equals(event.tenantId()))
                .toList();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).realm()).isEqualTo(AuditRealm.TENANT);
        assertThat(audits.get(0).actorId()).isEqualTo(SECADMIN);
        assertThat(audits.get(0).resourceId()).isEqualTo(SETTINGS_TENANT.toString());
        assertThat(audits.get(0).detail()).containsEntry("mfaRequired", true);
    }

    @Test
    void membersWithoutThePermissionCannotReadOrChangeTheSettings() {
        String member = (String) enter("secmember", null).get("accessToken");
        Response read = settings(member, HttpMethod.GET, null);
        assertThat(read.status()).isEqualTo(403);
        assertThat(read.code()).isEqualTo("auth.forbidden");
        Response write = settings(member, HttpMethod.PUT, Map.of("mfaRequired", true));
        assertThat(write.status()).isEqualTo(403);
        assertThat(write.code()).isEqualTo("auth.forbidden");
        assertThat(settings(null, HttpMethod.GET, null).status()).isEqualTo(401);
    }

    @Test
    void exchangeIntoARequiringTenantIsRejectedUntilTheLoginSessionBindsAnAuthenticator() {
        String cookie = login("req-exchange");
        Response rejected = exchange(authorizationCode(cookie), null);
        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.code()).isEqualTo("auth.mfaRequired");

        // 当场凭登录会话绑定，会话随之视为已完成 MFA，重新走授权码流程即可进入。
        Session bound = bindWith(null, cookie);
        Response entered = exchange(authorizationCode(bound.cookie()), null);
        assertThat(entered.status()).isEqualTo(200);
        assertThat(entered.data()).containsEntry("tenantId", REQUIRING_TENANT.toString());
        // 授权记为已完成 MFA，刷新同样放行。
        assertThat(post("/auth/refresh", Map.of("refreshToken", entered.data().get("refreshToken")), null)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void switchIntoARequiringTenantIsRejectedUntilTheAccessTokenBindsAnAuthenticator() {
        // 无要求的租户照常进入、刷新。
        Response entered = exchange(authorizationCode(login("req-switch")), SWITCH_IN_FREE_TENANT);
        assertThat(entered.status()).isEqualTo(200);
        assertThat(entered.data()).containsEntry("tenantId", FREE_TENANT.toString());
        Response refreshed =
                post("/auth/refresh", Map.of("refreshToken", entered.data().get("refreshToken")), null);
        assertThat(refreshed.status()).isEqualTo(200);

        String refreshToken = (String) refreshed.data().get("refreshToken");
        Response rejected = post(
                "/auth/switch", Map.of("refreshToken", refreshToken, "membershipId", SWITCH_IN_REQUIRING_TENANT), null);
        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.code()).isEqualTo("auth.mfaRequired");

        // 凭 Access Token 当场绑定后，当前授权视为已完成 MFA；被拒的刷新凭证未被消费，可直接重试。
        bindWith((String) refreshed.data().get("accessToken"), null);
        Response switched = post(
                "/auth/switch", Map.of("refreshToken", refreshToken, "membershipId", SWITCH_IN_REQUIRING_TENANT), null);
        assertThat(switched.status()).isEqualTo(200);
        assertThat(switched.data()).containsEntry("tenantId", REQUIRING_TENANT.toString());
    }

    @Test
    void aLoginSessionBindingReturnsTheAuthorizationParametersButAnAccessTokenBindingDoesNot() {
        // Token 被拒后页面可能早已刷新，内存里没有授权参数；凭登录会话绑定的响应要带上，才能重新走授权码流程。
        Session bound = bindWith(null, login("req-params"));
        assertThat(bound.confirmed())
                .containsEntry("clientId", "tenant-app")
                .containsEntry("redirectUri", "http://localhost:5174/");
        assertThat(exchange(authorizationCode(bound.cookie()), null).status()).isEqualTo(200);

        Map<?, ?> tokens = enter("req-params-token", null);
        Session byToken = bindWith((String) tokens.get("accessToken"), null);
        assertThat(byToken.confirmed()).containsEntry("clientId", null).containsEntry("redirectUri", null);
    }

    @Test
    void aLoginSessionUpgradedByBindingIsStillTerminatedByAPasswordChange() {
        String accessToken = (String) enter("req-session", null).get("accessToken");
        Session bound = bindWith(null, login("req-session"));
        assertThat(authorizeStatus(bound.cookie())).isEqualTo(302);

        Response changed = call(
                new Session(accessToken, null),
                "/auth/password/change",
                Map.of("currentPassword", PASSWORD, "newPassword", "Fresh-passphrase-2026"));
        assertThat(changed.status()).isEqualTo(200);

        assertThat(authorizeStatus(bound.cookie())).isNotEqualTo(302);
    }

    @Test
    void unbindingClearsTheMfaMarkOfExistingAuthorizationsAndLoginSessions() {
        Response entered = exchange(authorizationCode(login("req-unbind")), UNBIND_IN_FREE_TENANT);
        Session bound = bindWith((String) entered.data().get("accessToken"), null);
        Response switched = post(
                "/auth/switch",
                Map.of("refreshToken", entered.data().get("refreshToken"), "membershipId", UNBIND_IN_REQUIRING_TENANT),
                null);
        assertThat(switched.status()).isEqualTo(200);
        String refreshToken = (String) switched.data().get("refreshToken");
        String accessToken = (String) switched.data().get("accessToken");

        // 另一处以 Recovery Code 完成 MFA 的登录会话，解绑前可以签发新授权。
        String pending = post("/auth/login", Map.of("account", "req-unbind", "password", PASSWORD), null)
                .cookie();
        @SuppressWarnings("unchecked")
        List<String> recoveryCodes = (List<String>) bound.confirmed().get("recoveryCodes");
        String otherSession = post("/auth/login/mfa", Map.of("recoveryCode", recoveryCodes.get(0)), pending)
                .cookie();
        assertThat(authorizeStatus(otherSession)).isEqualTo(302);

        Response unbound = call(
                new Session(accessToken, null),
                "/auth/mfa/unbind",
                Map.of("code", Totp.code(bound.secret(), Totp.step(Instant.now()) + 1)));
        assertThat(unbound.status()).isEqualTo(200);

        Response refreshed = post("/auth/refresh", Map.of("refreshToken", refreshToken), null);
        assertThat(refreshed.status()).isEqualTo(403);
        assertThat(refreshed.code()).isEqualTo("auth.mfaRequired");
        Response rejected = post(
                "/auth/switch", Map.of("refreshToken", refreshToken, "membershipId", UNBIND_IN_REQUIRING_TENANT), null);
        assertThat(rejected.code()).isEqualTo("auth.mfaRequired");
        assertThat(authorizeStatus(otherSession)).isNotEqualTo(302);

        // 重新绑定后，当前授权恢复为已完成 MFA。
        bindWith(accessToken, null);
        assertThat(post("/auth/refresh", Map.of("refreshToken", refreshToken), null)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void anMfaResetClearsTheMfaMarkOfExistingAuthorizationsAndLoginSessions() {
        Response entered = exchange(authorizationCode(login("req-reset")), RESET_IN_FREE_TENANT);
        Session bound = bindWith((String) entered.data().get("accessToken"), null);
        Response switched = post(
                "/auth/switch",
                Map.of("refreshToken", entered.data().get("refreshToken"), "membershipId", RESET_IN_REQUIRING_TENANT),
                null);
        assertThat(switched.status()).isEqualTo(200);
        String refreshToken = (String) switched.data().get("refreshToken");

        // 另一处以 Recovery Code 完成 MFA 的登录会话，重置前可以签发新授权。
        String pending = post("/auth/login", Map.of("account", "req-reset", "password", PASSWORD), null)
                .cookie();
        @SuppressWarnings("unchecked")
        List<String> recoveryCodes = (List<String>) bound.confirmed().get("recoveryCodes");
        String otherSession = post("/auth/login/mfa", Map.of("recoveryCode", recoveryCodes.get(0)), pending)
                .cookie();
        assertThat(authorizeStatus(otherSession)).isEqualTo(302);

        mfaReset.resetIdentity(SUPER_ADMIN, RESET_IDENTITY);

        Response refreshed = post("/auth/refresh", Map.of("refreshToken", refreshToken), null);
        assertThat(refreshed.status()).isEqualTo(403);
        assertThat(refreshed.code()).isEqualTo("auth.mfaRequired");
        assertThat(authorizeStatus(otherSession)).isNotEqualTo(302);
    }

    @Test
    void tenantsWithoutTheRequirementAreUnaffected() {
        Map<?, ?> tokens = enter("req-free", null);
        assertThat(tokens.get("tenantId")).isEqualTo(FREE_TENANT.toString());
        assertThat(post("/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null)
                        .status())
                .isEqualTo(200);
    }

    record Response(int status, Map<?, ?> body, String cookie) {
        String code() {
            return (String) body.get("code");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }

    /** 绑定用的凭证；{@code confirmed} 与 {@code secret} 仅绑定完成后有值。 */
    record Session(String bearer, String cookie, Map<String, Object> confirmed, byte[] secret) {
        Session(String bearer, String cookie) {
            this(bearer, cookie, null, null);
        }
    }

    /** 未绑定的身份登录并进入其唯一或指定的 Membership，返回 Token 响应。 */
    private Map<?, ?> enter(String account, UUID membershipId) {
        Response tokens = exchange(authorizationCode(login(account)), membershipId);
        assertThat(tokens.status()).isEqualTo(200);
        return tokens.data();
    }

    private Response settings(String bearer, HttpMethod method, Object body) {
        var request = client().method(method).uri("/security-settings").headers(headers -> {
            if (bearer != null) {
                headers.setBearerAuth(bearer);
            }
        });
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class), null));
    }

    /** 以 Access Token 或登录会话绑定 Authenticator，返回绑定后的会话（会话标识可能换发）。 */
    private Session bindWith(String bearer, String cookie) {
        Session session = new Session(bearer, cookie);
        Response started = call(session, "/auth/mfa/bind/start");
        assertThat(started.status()).isEqualTo(200);
        byte[] secret = secretOf((String) started.data().get("otpauthUri"));
        Response confirmed = call(
                new Session(bearer, started.cookie()),
                "/auth/mfa/bind/confirm",
                Map.of("code", Totp.code(secret, Totp.step(Instant.now()))));
        assertThat(confirmed.status()).isEqualTo(201);
        return new Session(bearer, confirmed.cookie(), confirmed.data(), secret);
    }

    private Response call(Session session, String path) {
        return call(session, path, Map.of());
    }

    private Response call(Session session, String path, Object body) {
        return client().post()
                .uri(path)
                .headers(headers -> {
                    if (session.bearer() != null) {
                        headers.setBearerAuth(session.bearer());
                    }
                    if (session.cookie() != null) {
                        headers.add("Cookie", session.cookie());
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    String issued = res.getHeaders().getFirst("Set-Cookie");
                    return new Response(
                            res.getStatusCode().value(),
                            res.bodyTo(Map.class),
                            issued == null ? session.cookie() : issued.split(";")[0]);
                });
    }

    private String login(String account) {
        Response login = post("/auth/login", Map.of("account", account, "password", PASSWORD), null);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.data()).containsEntry("nextStep", "authorize");
        return login.cookie();
    }

    private Response exchange(String code, UUID membershipId) {
        var form = new HashMap<String, Object>(Map.of("code", code, "codeVerifier", VERIFIER));
        if (membershipId != null) {
            form.put("membershipId", membershipId);
        }
        return post("/auth/exchange", form, null);
    }

    private Response post(String path, Object body, String cookie) {
        return call(new Session(null, cookie), path, body);
    }

    private static byte[] secretOf(String otpauthUri) {
        String encoded = UriComponentsBuilder.fromUriString(otpauthUri)
                .build()
                .getQueryParams()
                .getFirst("secret");
        var out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : encoded.toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String authorizationCode(String cookie) {
        var response = authorizeRequest(cookie).retrieve().toBodilessEntity();
        return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private int authorizeStatus(String cookie) {
        return authorizeRequest(cookie)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private RestClient.RequestHeadersSpec<?> authorizeRequest(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            return client().get()
                    .uri(builder -> builder.path("/oauth2/authorize")
                            .queryParam("response_type", "code")
                            .queryParam("client_id", "tenant-app")
                            .queryParam("redirect_uri", "http://localhost:5174/")
                            .queryParam("scope", "tenant")
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
