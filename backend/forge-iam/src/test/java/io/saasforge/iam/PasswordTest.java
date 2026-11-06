package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.mfa.mapper.MfaProbeMapper;
import io.saasforge.iam.mfa.service.Totp;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.VerificationMessage;
import io.saasforge.iam.verification.service.VerificationPurpose;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.OperatorStateKeys;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * Password Policy 与自助改密码、忘记密码（账号安全工单 07）：四个设置密码的入口一致执行 Password Policy，存量密码不受影响；
 * 改密码或重设后其他会话与 Refresh Token 失效、已签发的 Access Token 因版本前进而失效。
 *
 * <p>每个用例使用 fixture 中的独立账号，用例顺序不影响结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/password_accounts.sql")
class PasswordTest {
    static final String PASSWORD = "Demo-password-123!";
    static final String OPERATOR_PASSWORD = "Operator-password-123!";
    static final String NEW_PASSWORD = "Fresh-passphrase-2026";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final UUID DEMO_TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID CHANGE = UUID.fromString("019a0110-0000-7000-8000-000000000402");
    static final UUID CHANGE_MEMBERSHIP = UUID.fromString("019a0110-0000-7000-8000-000000000412");
    static final UUID FORGOT = UUID.fromString("019a0110-0000-7000-8000-000000000403");
    static final UUID FORGOT_MEMBERSHIP = UUID.fromString("019a0110-0000-7000-8000-000000000413");
    static final UUID FORGOT_MFA = UUID.fromString("019a0110-0000-7000-8000-000000000404");
    static final UUID OPERATOR = UUID.fromString("019a0110-0000-7000-8000-000000000421");
    static final UUID OPERATOR_TO_RESET = UUID.fromString("019a0110-0000-7000-8000-000000000422");
    /** 最近一次发往各邮箱的验证码，按「用途:邮箱」记录。 */
    static final Map<String, String> SENT_CODES = new ConcurrentHashMap<>();

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
        registry.add("forge.iam.signing-key-file", () -> "target/test-password-signing.jwk");
    }

    @TestConfiguration
    static class CapturingEmail {
        @Bean
        EmailSender emailSender() {
            return (email, message) -> {
                if (message instanceof VerificationMessage verification) {
                    SENT_CODES.put(verification.purpose() + ":" + email, verification.code());
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    MfaProbeMapper probe;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void allFourEntriesEnforceThePasswordPolicyWithTheSameErrorCodes() {
        // 注册：违反时不消费验证码，换个密码可以继续注册。
        assertThat(call(null, "/auth/register/send-code", Map.of("target", "pwd-register@example.com"))
                        .status())
                .isEqualTo(200);
        String code = SENT_CODES.get("REGISTER:pwd-register@example.com");
        var register = Map.of("account", "pwdregister", "target", "pwd-register@example.com", "code", code);
        assertThat(rejections(
                        "pwd-register@example.com",
                        password -> call(null, "/auth/register", with(register, "password", password))))
                .containsExactly("password.tooShort", "password.tooCommon", "password.sameAsIdentifier");
        assertThat(call(null, "/auth/register", with(register, "password", NEW_PASSWORD))
                        .status())
                .isEqualTo(201);

        // Identity 自助改密码。
        Auth identity = tenantTokens("pwd-policy", PASSWORD);
        assertThat(rejections(
                        "pwd-policy",
                        password -> call(
                                identity,
                                "/auth/password/change",
                                Map.of("currentPassword", PASSWORD, "newPassword", password))))
                .containsExactly("password.tooShort", "password.tooCommon", "password.sameAsIdentifier");

        // 忘记密码重设。
        String resetToken = forgotVerify("pwd-policy@example.com").resetToken();
        assertThat(rejections(
                        "PWD-POLICY@example.com",
                        password -> call(
                                null,
                                "/auth/password/forgot/reset",
                                Map.of("resetToken", resetToken, "password", password))))
                .containsExactly("password.tooShort", "password.tooCommon", "password.sameAsIdentifier");

        // 管理员重置 Operator 密码。
        Auth admin = platformTokens("operator", OPERATOR_PASSWORD);
        assertThat(rejections(
                        "pwd-operator-reset",
                        password -> call(
                                admin,
                                HttpMethod.PUT,
                                "/platform/operators/" + OPERATOR_TO_RESET + "/password",
                                Map.of("password", password))))
                .containsExactly("password.tooShort", "password.tooCommon", "password.sameAsIdentifier");

        // 被拒的密码一个都没有生效。
        assertThat(tenantLoginStatus("pwd-policy", PASSWORD)).isEqualTo(200);
        assertThat(platformLoginStatus("pwd-operator-reset", OPERATOR_PASSWORD)).isEqualTo(200);
    }

    @Test
    void existingWeakPasswordStillSignsIn() {
        assertThat(tenantLoginStatus("pwd-legacy", "password")).isEqualTo(200);
    }

    @Test
    void identityChangesPasswordAndOtherSessionsAndRefreshTokensAreRevoked() {
        Tokens current = tenantTokenPair("pwd-change", PASSWORD);
        Tokens other = tenantTokenPair("pwd-change", PASSWORD);
        String otherSession = tenantSession("pwd-change", PASSWORD);
        Auth auth = new Auth(current.accessToken(), null);

        Response wrong = call(
                auth,
                "/auth/password/change",
                Map.of("currentPassword", "Not-the-password-1", "newPassword", NEW_PASSWORD));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.code()).isEqualTo("password.currentInvalid");

        Response changed =
                call(auth, "/auth/password/change", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));
        assertThat(changed.status()).isEqualTo(200);

        // 已签发的 Access Token 失效：网关比对的 Membership 版本已前进。
        long issued = version(current.accessToken());
        assertThat(Long.parseLong(
                        redisTemplate.opsForValue().get(MembershipStateKeys.version(DEMO_TENANT, CHANGE_MEMBERSHIP))))
                .isGreaterThan(issued);
        // 其他设备的 Refresh Token 与登录会话失效；发起改密码的这一授权保留，刷新后按新版本签发。
        assertThat(refreshStatus("/auth/refresh", other.refreshToken())).isEqualTo(401);
        assertThat(authorizeStatus(otherSession, "tenant-app", "http://localhost:5174/", "tenant"))
                .isNotEqualTo(302);
        Map<?, ?> refreshed = refresh("/auth/refresh", current.refreshToken());
        assertThat(version((String) refreshed.get("accessToken"))).isGreaterThan(issued);

        assertThat(tenantLoginStatus("pwd-change", PASSWORD)).isEqualTo(401);
        assertThat(tenantLoginStatus("pwd-change", NEW_PASSWORD)).isEqualTo(200);
        assertThat(passwordAudits(CHANGE)).containsExactly("password.change");
        assertThat(probe.identityUpdatedBy(CHANGE)).isEqualTo(CHANGE.toString());
    }

    @Test
    void operatorChangesPasswordAndOtherRefreshTokensAreRevoked() {
        Tokens current = platformTokenPair("pwd-operator", OPERATOR_PASSWORD);
        Tokens other = platformTokenPair("pwd-operator", OPERATOR_PASSWORD);
        Auth auth = new Auth(current.accessToken(), null);

        Response wrong = call(
                auth,
                "/platform/auth/password/change",
                Map.of("currentPassword", "Not-the-password-1", "newPassword", NEW_PASSWORD));
        assertThat(wrong.code()).isEqualTo("password.currentInvalid");
        // 改密码与管理员重置执行同一 Password Policy。
        assertThat(call(
                                auth,
                                "/platform/auth/password/change",
                                Map.of("currentPassword", OPERATOR_PASSWORD, "newPassword", "password"))
                        .code())
                .isEqualTo("password.tooCommon");

        Response changed = call(
                auth,
                "/platform/auth/password/change",
                Map.of("currentPassword", OPERATOR_PASSWORD, "newPassword", NEW_PASSWORD));
        assertThat(changed.status()).isEqualTo(200);

        long issued = version(current.accessToken());
        assertThat(Long.parseLong(redisTemplate.opsForValue().get(OperatorStateKeys.version(OPERATOR))))
                .isGreaterThan(issued);
        assertThat(refreshStatus("/platform/auth/refresh", other.refreshToken()))
                .isEqualTo(401);
        assertThat(version((String) refresh("/platform/auth/refresh", current.refreshToken())
                        .get("accessToken")))
                .isGreaterThan(issued);
        assertThat(platformLoginStatus("pwd-operator", OPERATOR_PASSWORD)).isEqualTo(401);
        assertThat(platformLoginStatus("pwd-operator", NEW_PASSWORD)).isEqualTo(200);
        assertThat(passwordAudits(OPERATOR, AuditRealm.PLATFORM)).containsExactly("password.change");
    }

    @Test
    void forgotPasswordRespondsTheSameForUnregisteredTargetsAndRevokesExistingTokens() {
        Tokens before = tenantTokenPair("pwd-forgot", PASSWORD);

        Response registered = call(null, "/auth/password/forgot/send-code", Map.of("target", "pwd-forgot@example.com"));
        Response unregistered =
                call(null, "/auth/password/forgot/send-code", Map.of("target", "nobody-pwd@example.com"));
        assertThat(registered.status()).isEqualTo(200);
        assertThat(unregistered.status()).isEqualTo(200);
        assertThat(unregistered.data()).isEqualTo(registered.data());
        // 未注册的目标不发送，校验结果与输错验证码相同。
        assertThat(SENT_CODES).doesNotContainKey(VerificationPurpose.PASSWORD_RESET + ":nobody-pwd@example.com");
        Response guessed = call(
                null, "/auth/password/forgot/verify", Map.of("target", "nobody-pwd@example.com", "code", "000000"));
        assertThat(guessed.code()).isEqualTo("verification.codeInvalid");

        String code = SENT_CODES.get(VerificationPurpose.PASSWORD_RESET + ":pwd-forgot@example.com");
        Response verified =
                call(null, "/auth/password/forgot/verify", Map.of("target", "pwd-forgot@example.com", "code", code));
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.data()).containsEntry("mfaRequired", false);
        String resetToken = (String) verified.data().get("resetToken");

        Response reset =
                call(null, "/auth/password/forgot/reset", Map.of("resetToken", resetToken, "password", NEW_PASSWORD));
        assertThat(reset.status()).isEqualTo(200);
        // 重设凭证只能用一次。
        assertThat(call(null, "/auth/password/forgot/reset", Map.of("resetToken", resetToken, "password", NEW_PASSWORD))
                        .code())
                .isEqualTo("password.resetExpired");

        assertThat(refreshStatus("/auth/refresh", before.refreshToken())).isEqualTo(401);
        assertThat(Long.parseLong(
                        redisTemplate.opsForValue().get(MembershipStateKeys.version(DEMO_TENANT, FORGOT_MEMBERSHIP))))
                .isGreaterThan(version(before.accessToken()));
        assertThat(tenantLoginStatus("pwd-forgot", PASSWORD)).isEqualTo(401);
        assertThat(tenantLoginStatus("pwd-forgot", NEW_PASSWORD)).isEqualTo(200);
        assertThat(passwordAudits(FORGOT)).containsExactly("password.reset");
    }

    @Test
    void forgotPasswordRequiresMfaWhenAnAuthenticatorIsBound() {
        Bound bound = bind("pwd-forgot-mfa");
        Verified verified = forgotVerify("pwd-forgot-mfa@example.com");
        assertThat(verified.mfaRequired()).isTrue();

        var body = Map.of("resetToken", verified.resetToken(), "password", NEW_PASSWORD);
        Response missing = call(null, "/auth/password/forgot/reset", body);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("password.mfaRequired");
        assertThat(call(null, "/auth/password/forgot/reset", with(body, "code", wrongCode(bound.secret())))
                        .code())
                .isEqualTo("mfa.codeInvalid");
        // 动态码错误时重设凭证保留，可以改用 Recovery Code。
        Response reset = call(
                null,
                "/auth/password/forgot/reset",
                with(body, "recoveryCode", bound.recoveryCodes().get(0)));
        assertThat(reset.status()).isEqualTo(200);

        assertThat(tenantLoginStatus("pwd-forgot-mfa", PASSWORD)).isEqualTo(401);
        assertThat(tenantLoginStatus("pwd-forgot-mfa", NEW_PASSWORD)).isEqualTo(200);
        assertThat(passwordAudits(FORGOT_MFA)).containsExactly("password.reset");
        // MFA 步骤与登录第二步一样留痕，并区分动态码与 Recovery Code。
        assertThat(mfaAudits(FORGOT_MFA))
                .containsExactly("totp:" + AuditOutcome.FAILURE, "recoveryCode:" + AuditOutcome.SUCCESS);
        // 忘记密码没有认证上下文，标准列操作人为系统。
        assertThat(probe.identityUpdatedBy(FORGOT_MFA)).isNull();
    }

    // ---- 辅助 ----

    record Auth(String bearer, String cookie) {}

    record Tokens(String accessToken, String refreshToken) {}

    record Verified(String resetToken, boolean mfaRequired) {}

    record Bound(byte[] secret, List<String> recoveryCodes) {}

    record Response(int status, Map<?, ?> body) {
        String code() {
            return (String) body.get("code");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }

    /** 依次以过短、弱、与登录标识相同的密码调用，返回各自的错误码。 */
    private static List<String> rejections(String identifier, Function<String, Response> attempt) {
        return Stream.of("Ab3$xyz", "iloveyou", identifier)
                .map(password -> {
                    Response response = attempt.apply(password);
                    assertThat(response.status()).isEqualTo(400);
                    return response.code();
                })
                .toList();
    }

    /** 复制请求体并设置一个字段。 */
    private static Map<String, Object> with(Map<String, ?> body, String key, Object value) {
        var copy = new HashMap<String, Object>(body);
        copy.put(key, value);
        return copy;
    }

    private Response call(Auth auth, String path, Map<String, ?> body) {
        return call(auth, HttpMethod.POST, path, body);
    }

    private Response call(Auth auth, HttpMethod method, String path, Map<String, ?> body) {
        return RestClient.create(baseUrl())
                .method(method)
                .uri(path)
                .headers(headers -> {
                    if (auth != null && auth.bearer() != null) headers.setBearerAuth(auth.bearer());
                    if (auth != null && auth.cookie() != null) headers.add("Cookie", auth.cookie());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    private Verified forgotVerify(String email) {
        assertThat(call(null, "/auth/password/forgot/send-code", Map.of("target", email))
                        .status())
                .isEqualTo(200);
        String code = SENT_CODES.get(VerificationPurpose.PASSWORD_RESET + ":" + email);
        Response verified = call(null, "/auth/password/forgot/verify", Map.of("target", email, "code", code));
        assertThat(verified.status()).isEqualTo(200);
        return new Verified((String) verified.data().get("resetToken"), (Boolean)
                verified.data().get("mfaRequired"));
    }

    /** 该账号产出的 password.* 审计动作，按发生顺序；凭证变更不属于任何租户。 */
    private List<String> mfaAudits(UUID actor) {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> actor.equals(event.actorId()) && event.action().equals("auth.mfa"))
                .map(event -> event.detail().get("method") + ":" + event.outcome())
                .toList();
    }

    private List<String> passwordAudits(UUID actor) {
        return passwordAudits(actor, AuditRealm.TENANT);
    }

    private List<String> passwordAudits(UUID actor, AuditRealm realm) {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> actor.equals(event.actorId()) && event.action().startsWith("password."))
                .peek(event -> {
                    assertThat(event.realm()).isEqualTo(realm);
                    assertThat(event.tenantId()).isNull();
                    assertThat(event.resourceId()).isEqualTo(actor.toString());
                })
                .map(AuditEvent::action)
                .toList();
    }

    /** Access Token 中的版本声明。 */
    private static long version(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        return ((Number) JSON.readValue(payload, Map.class).get("ver")).longValue();
    }

    private Auth tenantTokens(String account, String password) {
        return new Auth(tenantTokenPair(account, password).accessToken(), null);
    }

    private Tokens tenantTokenPair(String account, String password) {
        String cookie = tenantSession(account, password);
        String code = authorizationCode(cookie, "tenant-app", "http://localhost:5174/", "tenant");
        Map<?, ?> tokens = tokenCall("/auth/exchange", Map.of("code", code, "codeVerifier", VERIFIER));
        return new Tokens((String) tokens.get("accessToken"), (String) tokens.get("refreshToken"));
    }

    private Auth platformTokens(String account, String password) {
        return new Auth(platformTokenPair(account, password).accessToken(), null);
    }

    private Tokens platformTokenPair(String account, String password) {
        String cookie = session("/platform/auth/login", account, password);
        String code = authorizationCode(cookie, "platform-console", "http://localhost:5173/", "platform");
        Map<?, ?> tokens = tokenCall("/platform/auth/exchange", Map.of("code", code, "codeVerifier", VERIFIER));
        return new Tokens((String) tokens.get("accessToken"), (String) tokens.get("refreshToken"));
    }

    private String tenantSession(String account, String password) {
        return session("/auth/login", account, password);
    }

    private String session(String path, String account, String password) {
        var login = noRedirectClient()
                .post()
                .uri(path)
                .body(Map.of("account", account, "password", password))
                .retrieve()
                .toEntity(Map.class);
        return login.getHeaders().getFirst("Set-Cookie").split(";")[0];
    }

    private int tenantLoginStatus(String account, String password) {
        return loginStatus("/auth/login", account, password);
    }

    private int platformLoginStatus(String account, String password) {
        return loginStatus("/platform/auth/login", account, password);
    }

    private int loginStatus(String path, String account, String password) {
        return noRedirectClient()
                .post()
                .uri(path)
                .body(Map.of("account", account, "password", password))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private int refreshStatus(String path, String refreshToken) {
        return noRedirectClient()
                .post()
                .uri(path)
                .body(Map.of("refreshToken", refreshToken))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private Map<?, ?> refresh(String path, String refreshToken) {
        return tokenCall(path, Map.of("refreshToken", refreshToken));
    }

    private Map<?, ?> tokenCall(String path, Map<String, ?> body) {
        return (Map<?, ?>) RestClient.create(baseUrl())
                .post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(Map.class)
                .get("data");
    }

    /** 尚未绑定的身份凭登录会话绑定 Authenticator。 */
    private Bound bind(String account) {
        Auth session = new Auth(null, tenantSession(account, PASSWORD));
        Response started = call(session, "/auth/mfa/bind/start", Map.of());
        byte[] secret = secretOf((String) started.data().get("otpauthUri"));
        Response confirmed =
                call(session, "/auth/mfa/bind/confirm", Map.of("code", Totp.code(secret, Totp.step(Instant.now()))));
        assertThat(confirmed.status()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) confirmed.data().get("recoveryCodes");
        return new Bound(secret, codes);
    }

    /** 与容差窗口内任何一步都不同的码。 */
    private static String wrongCode(byte[] secret) {
        long step = Totp.step(Instant.now());
        var valid = List.of(
                Totp.code(secret, step - 1),
                Totp.code(secret, step),
                Totp.code(secret, step + 1),
                Totp.code(secret, step + 2));
        for (int candidate = 0; ; candidate++) {
            String code = "%06d".formatted(candidate);
            if (!valid.contains(code)) return code;
        }
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

    private int authorizeStatus(String cookie, String clientId, String redirectUri, String scope) {
        return authorizeRequest(cookie, clientId, redirectUri, scope)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private String authorizationCode(String cookie, String clientId, String redirectUri, String scope) {
        var response = authorizeRequest(cookie, clientId, redirectUri, scope)
                .retrieve()
                .toBodilessEntity();
        return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private RestClient.RequestHeadersSpec<?> authorizeRequest(
            String cookie, String clientId, String redirectUri, String scope) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            return noRedirectClient()
                    .get()
                    .uri(builder -> builder.path("/oauth2/authorize")
                            .queryParam("response_type", "code")
                            .queryParam("client_id", clientId)
                            .queryParam("redirect_uri", redirectUri)
                            .queryParam("scope", scope)
                            .queryParam("state", "test-state")
                            .queryParam("code_challenge_method", "S256")
                            .queryParam("code_challenge", challenge)
                            .build())
                    .header("Cookie", cookie);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private RestClient noRedirectClient() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
