package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.ClientIpHeader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Login Lockout（账号安全工单 06）：两个 Realm 的认证失败按登录标识或 Identity、Operator 计数，达到阈值后临时锁定，另按客户端 IP 限速。
 *
 * <p>阈值调小、锁定时长缩短以便测试；每个用例使用独立账号与独立客户端 IP（网关转发的 {@link ClientIpHeader#NAME}），计数互不影响；
 * {@code forward-headers-strategy} 与部署配置（forge-common.yaml）相同。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "server.forward-headers-strategy=framework",
            "forge.iam.lockout.tenant.max-failures=3",
            "forge.iam.lockout.tenant.lock-duration=3s",
            "forge.iam.lockout.tenant.ip-max-failures=8",
            "forge.iam.lockout.platform.max-failures=3",
            "forge.iam.lockout.platform.lock-duration=3s",
            "forge.iam.lockout.platform.ip-max-failures=8"
        })
@Sql("/fixtures/lockout_accounts.sql")
class LoginLockoutTest {
    static final String PASSWORD = "Demo-password-123!";
    static final String OPERATOR_PASSWORD = PlatformMfaClient.PASSWORD;
    static final UUID LOCK_MFA = UUID.fromString("019a0110-0000-7000-8000-000000000503");
    static final AtomicInteger IPS = new AtomicInteger();
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
        registry.add("forge.iam.signing-key-file", () -> "target/test-lockout-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    PlatformMfaClient platform;

    @BeforeEach
    void platformClient() {
        platform = new PlatformMfaClient(port, outbox);
    }

    @Test
    void unknownAndExistingIdentifiersAreCountedAndLockedAlike() {
        String ip = newIp();
        for (String account : new String[] {"lock-known", "lock-nobody"}) {
            for (int i = 0; i < 3; i++) {
                Response failed = post("/auth/login", login(account, "Wrong-password-1"), null, null, ip);
                assertThat(failed.status()).isEqualTo(401);
                assertThat(failed.code()).isEqualTo("auth.failed");
            }
            Response locked = post("/auth/login", login(account, PASSWORD), null, null, ip);
            assertThat(locked.status()).isEqualTo(429);
            assertThat(locked.code()).isEqualTo("auth.locked");
            assertThat(Integer.parseInt(locked.retryAfter())).isBetween(1, 3);
            assertThat((String) locked.body().get("userTip")).contains("秒");
        }
        // 触发锁定写审计；不存在的标识没有可确定的身份。
        assertThat(lockAudits()).anySatisfy(event -> {
            assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
            assertThat(event.actorId()).isNull();
            assertThat(event.detail()).containsEntry("account", "lock-known");
        });
        assertThat(lockAudits())
                .anySatisfy(event -> assertThat(event.detail()).containsEntry("account", "lock-nobody"));
    }

    @Test
    void theLockExpiresAutomatically() throws InterruptedException {
        String ip = newIp();
        for (int i = 0; i < 3; i++) {
            post("/auth/login", login("lock-expiry", "Wrong-password-1"), null, null, ip);
        }
        // 大小写与首尾空白变体是同一个登录标识。
        Response locked = post("/auth/login", login(" LOCK-EXPIRY", PASSWORD), null, null, ip);
        assertThat(locked.code()).isEqualTo("auth.locked");

        Thread.sleep(Integer.parseInt(locked.retryAfter()) * 1000L + 200);
        Response login = post("/auth/login", login("lock-expiry", PASSWORD), null, null, ip);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.data()).containsEntry("nextStep", "authorize");
    }

    @Test
    void failuresFromOneClientAddressLockThatAddressAcrossIdentifiers() {
        String ip = newIp();
        // 每个标识都未达阈值，合计达到 IP 的阈值。
        for (int i = 0; i < 8; i++) {
            Response failed =
                    post("/auth/login", login("lock-ip-guess-" + (i / 2), "Wrong-password-1"), null, null, ip);
            assertThat(failed.code()).isEqualTo("auth.failed");
        }
        Response limited = post("/auth/login", login("lock-ip", PASSWORD), null, null, ip);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("auth.locked");
        assertThat(limited.retryAfter()).isNotNull();
        // 其他地址不受影响；X-Forwarded-For 不被采信，否则同一个 198.51.100.99 早已被锁。
        assertThat(post("/auth/login", login("lock-ip", PASSWORD), null, null, newIp())
                        .status())
                .isEqualTo(200);
        assertThat(lockAudits()).anySatisfy(event -> assertThat(event.detail()).containsEntry("ip", ip));
    }

    @Test
    void codeAndRecoveryCodeFailuresAreCountedOnTheIdentity() {
        String ip = newIp();
        byte[] secret = bindBySession("lock-mfa", ip);
        String pending =
                post("/auth/login", login("lock-mfa", PASSWORD), null, null, ip).cookie();
        assertThat(post("/auth/login/mfa", Map.of("code", PlatformMfaClient.wrongCode(secret)), pending, null, ip)
                        .code())
                .isEqualTo("mfa.codeInvalid");
        assertThat(post("/auth/login/mfa", Map.of("recoveryCode", "AAAA-BBBB-CCCC-DDDD"), pending, null, ip)
                        .code())
                .isEqualTo("mfa.recoveryCodeInvalid");
        assertThat(post("/auth/login/mfa", Map.of("code", PlatformMfaClient.wrongCode(secret)), pending, null, ip)
                        .code())
                .isEqualTo("mfa.codeInvalid");

        // 锁定期内正确的动态码同样被拒，换用其他登录标识通过 First Factor 也被拒。
        Response locked = post("/auth/login/mfa", Map.of("code", PlatformMfaClient.code(secret, 1)), pending, null, ip);
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.code()).isEqualTo("auth.locked");
        assertThat(locked.retryAfter()).isNotNull();
        assertThat(post("/auth/login", login("lock-mfa@example.com", PASSWORD), null, null, ip)
                        .code())
                .isEqualTo("auth.locked");
        assertThat(lockAudits()).anySatisfy(event -> assertThat(event.actorId()).isEqualTo(LOCK_MFA));
    }

    @Test
    void bindingConfirmationFailuresAreCountedOnTheIdentity() {
        String ip = newIp();
        String session = post("/auth/login", login("lock-mfa-token", PASSWORD), null, null, ip)
                .cookie();
        byte[] secret = PlatformMfaClient.secretOf((String)
                post("/auth/mfa/bind/start", null, session, null, ip).data().get("otpauthUri"));
        for (int i = 0; i < 3; i++) {
            Response wrong = post(
                    "/auth/mfa/bind/confirm", Map.of("code", PlatformMfaClient.wrongCode(secret)), session, null, ip);
            assertThat(wrong.code()).isEqualTo("mfa.codeInvalid");
        }
        Response locked =
                post("/auth/mfa/bind/confirm", Map.of("code", PlatformMfaClient.code(secret, 0)), session, null, ip);
        assertThat(locked.code()).isEqualTo("auth.locked");
    }

    @Test
    void wrongCurrentPasswordsAreCountedOnTheIdentity() {
        String ip = newIp();
        String token = tenantAccessToken(post("/auth/login", login("lock-change", PASSWORD), null, null, ip)
                .cookie());
        var change = Map.of("currentPassword", "Wrong-password-1", "newPassword", "Another-password-456!");
        for (int i = 0; i < 3; i++) {
            assertThat(post("/auth/password/change", change, null, token, ip).code())
                    .isEqualTo("password.currentInvalid");
        }
        var correct = Map.of("currentPassword", PASSWORD, "newPassword", "Another-password-456!");
        assertThat(post("/auth/password/change", correct, null, token, ip).code())
                .isEqualTo("auth.locked");
        assertThat(post("/auth/login", login("lock-change", PASSWORD), null, null, ip)
                        .code())
                .isEqualTo("auth.locked");
    }

    @Test
    void passwordResetCodeFailuresAreCountedOnTheTarget() {
        String ip = newIp();
        for (int i = 0; i < 3; i++) {
            Response wrong = post(
                    "/auth/password/forgot/verify",
                    Map.of("target", "Lock-Reset@example.com", "code", "000000"),
                    null,
                    null,
                    ip);
            assertThat(wrong.code()).startsWith("verification.");
        }
        // 与登录共用同一登录标识的计数：锁定期内邮箱登录也被拒。
        assertThat(post("/auth/login", login("lock-reset@example.com", PASSWORD), null, null, ip)
                        .code())
                .isEqualTo("auth.locked");
        assertThat(post("/auth/login", login("lock-reset", PASSWORD), null, null, ip)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void operatorPasswordFailuresLockTheAccount() {
        String ip = newIp();
        for (int i = 0; i < 3; i++) {
            assertThat(post("/platform/auth/login", login("op-lock-password", "Wrong-password-1"), null, null, ip)
                            .code())
                    .isEqualTo("auth.failed");
        }
        Response locked = post("/platform/auth/login", login("op-lock-password", OPERATOR_PASSWORD), null, null, ip);
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.code()).isEqualTo("auth.locked");
        assertThat(lockAudits()).anySatisfy(event -> {
            assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
            assertThat(event.detail()).containsEntry("account", "op-lock-password");
        });
        // 两个 Realm 分别计数：同名的 Tenant 账号不受影响（此处不存在，照常返回 auth.failed）。
        assertThat(post("/auth/login", login("op-lock-password", "Wrong-password-1"), null, null, ip)
                        .code())
                .isEqualTo("auth.failed");
    }

    @Test
    void operatorCodeFailuresAreCountedOnTheOperator() {
        String ip = newIp();
        String token = platform.accessToken(
                post("/platform/auth/login", login("op-lock-mfa", OPERATOR_PASSWORD), null, null, ip)
                        .cookie());
        byte[] secret = PlatformMfaClient.secretOf((String) post("/platform/auth/mfa/bind/start", null, null, token, ip)
                .data()
                .get("otpauthUri"));
        assertThat(post(
                                "/platform/auth/mfa/bind/confirm",
                                Map.of("code", PlatformMfaClient.code(secret, 0)),
                                null,
                                token,
                                ip)
                        .status())
                .isEqualTo(201);
        // 凭 Access Token 的入口与登录第二步计在同一个 Operator 上。
        assertThat(post(
                                "/platform/auth/mfa/recovery-codes/regenerate",
                                Map.of("code", PlatformMfaClient.wrongCode(secret)),
                                null,
                                token,
                                ip)
                        .code())
                .isEqualTo("mfa.codeInvalid");
        String pending = post("/platform/auth/login", login("op-lock-mfa", OPERATOR_PASSWORD), null, null, ip)
                .cookie();
        for (int i = 0; i < 2; i++) {
            assertThat(post(
                                    "/platform/auth/login/mfa",
                                    Map.of("code", PlatformMfaClient.wrongCode(secret)),
                                    pending,
                                    null,
                                    ip)
                            .code())
                    .isEqualTo("mfa.codeInvalid");
        }
        Response locked =
                post("/platform/auth/login/mfa", Map.of("code", PlatformMfaClient.code(secret, 1)), pending, null, ip);
        assertThat(locked.code()).isEqualTo("auth.locked");
        assertThat(post("/platform/auth/login", login("op-lock-mfa", OPERATOR_PASSWORD), null, null, ip)
                        .code())
                .isEqualTo("auth.locked");
    }

    @Test
    void wrongCurrentPasswordsAreCountedOnTheOperator() {
        String ip = newIp();
        String token = platform.accessToken(
                post("/platform/auth/login", login("op-lock-change", OPERATOR_PASSWORD), null, null, ip)
                        .cookie());
        var change = Map.of("currentPassword", "Wrong-password-1", "newPassword", "Another-password-456!");
        for (int i = 0; i < 3; i++) {
            assertThat(post("/platform/auth/password/change", change, null, token, ip)
                            .code())
                    .isEqualTo("password.currentInvalid");
        }
        assertThat(post("/platform/auth/login", login("op-lock-change", OPERATOR_PASSWORD), null, null, ip)
                        .code())
                .isEqualTo("auth.locked");
    }

    /** 凭登录会话绑定 Authenticator，确认时用掉当前步长的动态码。 */
    private byte[] bindBySession(String account, String ip) {
        String session =
                post("/auth/login", login(account, PASSWORD), null, null, ip).cookie();
        byte[] secret = PlatformMfaClient.secretOf((String)
                post("/auth/mfa/bind/start", null, session, null, ip).data().get("otpauthUri"));
        assertThat(post("/auth/mfa/bind/confirm", Map.of("code", PlatformMfaClient.code(secret, 0)), session, null, ip)
                        .status())
                .isEqualTo(201);
        return secret;
    }

    private String tenantAccessToken(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(PlatformMfaClient.VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var redirect = client().get()
                    .uri(builder -> builder.path("/oauth2/authorize")
                            .queryParam("response_type", "code")
                            .queryParam("client_id", "tenant-app")
                            .queryParam("redirect_uri", "http://localhost:5174/")
                            .queryParam("scope", "tenant")
                            .queryParam("state", "test-state")
                            .queryParam("code_challenge_method", "S256")
                            .queryParam("code_challenge", challenge)
                            .build())
                    .header("Cookie", cookie)
                    .retrieve()
                    .toBodilessEntity();
            String code = UriComponentsBuilder.fromUri(redirect.getHeaders().getLocation())
                    .build()
                    .getQueryParams()
                    .getFirst("code");
            Response exchanged = post(
                    "/auth/exchange",
                    Map.of("code", code, "codeVerifier", PlatformMfaClient.VERIFIER),
                    null,
                    null,
                    null);
            return (String) exchanged.data().get("accessToken");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Response(int status, Map<?, ?> body, String retryAfter, String cookie) {
        String code() {
            return (String) body.get("code");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }

    private List<AuditEvent> lockAudits() {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("auth.locked"))
                .toList();
    }

    private static Map<String, String> login(String account, String password) {
        return Map.of("account", account, "password", password);
    }

    /** 每个用例独占的客户端 IP，IP 限速的计数互不影响。 */
    private static String newIp() {
        return "203.0.113." + IPS.incrementAndGet();
    }

    /** 模拟经网关转发的请求：客户端 IP 由网关写入专用请求头，X-Forwarded-For 可伪造。 */
    private Response post(String path, Object body, String cookie, String bearer, String ip) {
        return client().post()
                .uri(path)
                .headers(headers -> {
                    if (cookie != null) headers.add("Cookie", cookie);
                    if (bearer != null) headers.setBearerAuth(bearer);
                    if (ip != null) {
                        headers.add(ClientIpHeader.NAME, ip);
                        headers.add("X-Forwarded-For", "198.51.100.99");
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? Map.of() : body)
                .exchange((req, res) -> {
                    String issued = res.getHeaders().getFirst("Set-Cookie");
                    return new Response(
                            res.getStatusCode().value(),
                            res.bodyTo(Map.class),
                            res.getHeaders().getFirst("Retry-After"),
                            issued == null ? cookie : issued.split(";")[0]);
                });
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
