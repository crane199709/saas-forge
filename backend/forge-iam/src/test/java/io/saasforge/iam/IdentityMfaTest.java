package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.mfa.mapper.MfaProbeMapper;
import io.saasforge.iam.mfa.service.Totp;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.iam.security.service.MfaAuthority;
import io.saasforge.iam.security.service.RedisAuthorizationService;
import io.saasforge.iam.session.model.PendingMfa;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.VerificationMessage;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Identity 的 Authenticator 与 Recovery Code（账号安全工单 01）：两步绑定、重新生成、解绑、防重放与审计；以及 Tenant Realm
 * 两步登录（工单 02）：已绑定者通过 First Factor 后进入待 MFA 状态，提交动态码或 Recovery Code 后才能走授权码流程。
 *
 * <p>每个用例使用 fixture 中的独立身份，绑定状态互不影响，用例顺序不影响结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/mfa_identities.sql")
class IdentityMfaTest {
    static final String PASSWORD = "Demo-password-123!";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final UUID BIND = UUID.fromString("019a0110-0000-7000-8000-000000000301");
    static final UUID REPLAY = UUID.fromString("019a0110-0000-7000-8000-000000000303");
    static final UUID REGENERATE = UUID.fromString("019a0110-0000-7000-8000-000000000304");
    static final UUID UNBIND = UUID.fromString("019a0110-0000-7000-8000-000000000305");
    static final UUID GUARD = UUID.fromString("019a0110-0000-7000-8000-000000000306");
    static final UUID LOGIN = UUID.fromString("019a0110-0000-7000-8000-000000000308");
    static final UUID CODE_LOGIN = UUID.fromString("019a0110-0000-7000-8000-000000000309");
    static final UUID RECOVERY = UUID.fromString("019a0110-0000-7000-8000-00000000030a");
    static final UUID EXPIRED = UUID.fromString("019a0110-0000-7000-8000-00000000030b");
    static final UUID UNBOUND = UUID.fromString("019a0110-0000-7000-8000-00000000030d");
    static final UUID LOGIN_IN_DEMO_TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000318");
    static final UUID LOGIN_IN_SECOND_TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000328");
    static final UUID SECOND_TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000004");
    /** 最近一次发往各邮箱的登录验证码。 */
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
        registry.add("forge.iam.signing-key-file", () -> "target/test-mfa-signing.jwk");
    }

    /** 截获邮件验证码，供验证码登录用例使用。 */
    @TestConfiguration
    static class CapturingEmail {
        @Bean
        EmailSender emailSender() {
            return (email, message) -> {
                if (message instanceof VerificationMessage verification) {
                    SENT_CODES.put(email, verification.code());
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MfaProbeMapper probe;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    RedisAuthorizationService authorizations;

    @Autowired
    SessionRepository<? extends Session> httpSessions;

    @Test
    void bindingTakesEffectOnlyAfterTheFirstCodeAndReturnsRecoveryCodesOnce() {
        Auth auth = accessToken("mfa-bind");
        assertThat(bound(auth)).isFalse();
        Response started = call(auth, "/auth/mfa/bind/start", null);
        assertThat(started.status()).isEqualTo(200);
        String uri = (String) started.data().get("otpauthUri");
        assertThat(uri)
                .startsWith("otpauth://totp/SaaS%20Forge:mfa-bind?secret=")
                .contains("&issuer=SaaS%20Forge");
        byte[] secret = secretOf(uri);
        // 提交首个动态码之前尚未绑定。
        assertThat(probe.activeSecretHex(BIND)).isNull();

        Response wrong = call(auth, "/auth/mfa/bind/confirm", Map.of("code", wrongCode(secret)));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.code()).isEqualTo("mfa.codeInvalid");
        assertThat(probe.activeSecretHex(BIND)).isNull();

        assertThat(bound(auth)).isFalse();

        Response confirmed = call(auth, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret)));
        assertThat(confirmed.status()).isEqualTo(201);
        assertThat(bound(auth)).isTrue();
        List<String> codes = recoveryCodes(confirmed);
        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        assertThat(codes).allMatch(code -> code.matches("[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}"));

        // 密钥只存密文，Recovery Code 只存哈希。
        assertThat(probe.activeSecretHex(BIND))
                .isNotNull()
                .doesNotContain(HexFormat.of().formatHex(secret));
        List<String> stored = probe.activeRecoveryCodeHashes(BIND);
        assertThat(stored).hasSize(10).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        for (String code : codes) {
            assertThat(stored).noneMatch(hash -> hash.contains(code) || hash.contains(code.replace("-", "")));
        }

        // Recovery Code 没有再次取得的途径：已绑定时不能重新开始绑定，也不能再次确认。
        assertThat(call(auth, "/auth/mfa/bind/start", null).code()).isEqualTo("mfa.alreadyBound");
        Response again = call(auth, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret)));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.data()).isNull();
    }

    @Test
    void loginSessionCanBindButOtherEndpointsRequireATenantAccessToken() {
        Auth session = loginSession("mfa-session");
        byte[] secret = start(session);
        Response confirmed = call(session, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret)));
        assertThat(confirmed.status()).isEqualTo(201);
        assertThat(recoveryCodes(confirmed)).hasSize(10);

        Response regenerate = call(session, "/auth/mfa/recovery-codes/regenerate", Map.of("code", nextCode(secret)));
        assertThat(regenerate.status()).isEqualTo(401);
        assertThat(call(session, "/auth/mfa/unbind", Map.of("code", nextCode(secret)))
                        .status())
                .isEqualTo(401);
        assertThat(call(null, "/auth/mfa/bind/start", null).code()).isEqualTo("auth.unauthenticated");
        // 同一套登录会话也承载 Operator 登录，Operator 会话不能为 Identity 绑定。
        Response operator = call(operatorSession(), "/auth/mfa/bind/start", null);
        assertThat(operator.status()).isEqualTo(403);
        assertThat(operator.code()).isEqualTo("auth.forbidden");
    }

    @Test
    void aCodeCanOnlyBeUsedOnceWithinItsValidity() {
        Auth auth = accessToken("mfa-replay");
        byte[] secret = start(auth);
        String first = currentCode(secret);
        assertThat(call(auth, "/auth/mfa/bind/confirm", Map.of("code", first)).status())
                .isEqualTo(201);
        // 确认绑定用掉的码不能再用于其他操作。
        Response replayed = call(auth, "/auth/mfa/recovery-codes/regenerate", Map.of("code", first));
        assertThat(replayed.status()).isEqualTo(400);
        assertThat(replayed.code()).isEqualTo("mfa.codeInvalid");
        // 时钟容差内更晚一步的码可以使用，但同样只能用一次。
        String next = nextCode(secret);
        assertThat(call(auth, "/auth/mfa/recovery-codes/regenerate", Map.of("code", next))
                        .status())
                .isEqualTo(201);
        assertThat(call(auth, "/auth/mfa/unbind", Map.of("code", next)).code()).isEqualTo("mfa.codeInvalid");
        assertThat(probe.activeSecretHex(REPLAY)).isNotNull();
    }

    @Test
    void concurrentSubmissionsOfTheSameCodeOnlySucceedOnce() throws Exception {
        Auth auth = accessToken("mfa-concurrent");
        byte[] secret = start(auth);
        assertThat(call(auth, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret)))
                        .status())
                .isEqualTo(201);
        String next = nextCode(secret);
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            ready.await();
            return call(auth, "/auth/mfa/recovery-codes/regenerate", Map.of("code", next))
                    .status();
        };
        try {
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 400);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void regeneratingRecoveryCodesVoidsTheWholePreviousSet() {
        Auth auth = accessToken("mfa-regenerate");
        byte[] secret = start(auth);
        List<String> original =
                recoveryCodes(call(auth, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret))));
        List<String> originalHashes = probe.activeRecoveryCodeHashes(REGENERATE);

        Response regenerated = call(auth, "/auth/mfa/recovery-codes/regenerate", Map.of("code", nextCode(secret)));
        assertThat(regenerated.status()).isEqualTo(201);
        assertThat(recoveryCodes(regenerated)).hasSize(10).doesNotContainAnyElementsOf(original);
        // 旧组全部作废，只剩新的一组。
        assertThat(probe.activeRecoveryCodeHashes(REGENERATE)).hasSize(10).doesNotContainAnyElementsOf(originalHashes);
        assertThat(mfaAudits(REGENERATE)).containsExactly("mfa.bind", "mfa.recoveryCodesRegenerate");
    }

    @Test
    void unbindingRequiresACodeAndRemovesTheAuthenticatorWithItsRecoveryCodes() {
        Auth auth = accessToken("mfa-unbind");
        byte[] secret = start(auth);
        assertThat(call(auth, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret)))
                        .status())
                .isEqualTo(201);

        Response wrong = call(auth, "/auth/mfa/unbind", Map.of("code", wrongCode(secret)));
        assertThat(wrong.code()).isEqualTo("mfa.codeInvalid");
        assertThat(probe.activeSecretHex(UNBIND)).isNotNull();

        Response unbound = call(auth, "/auth/mfa/unbind", Map.of("code", nextCode(secret)));
        assertThat(unbound.status()).isEqualTo(200);
        assertThat(probe.activeSecretHex(UNBIND)).isNull();
        assertThat(probe.activeRecoveryCodeHashes(UNBIND)).isEmpty();
        assertThat(call(auth, "/auth/mfa/recovery-codes/regenerate", Map.of("code", nextCode(secret)))
                        .code())
                .isEqualTo("mfa.notBound");
        // 解绑后可以重新绑定。
        assertThat(call(auth, "/auth/mfa/bind/start", null).status()).isEqualTo(200);
        assertThat(mfaAudits(UNBIND)).containsExactly("mfa.bind", "mfa.unbind");
    }

    @Test
    void operationsOutOfOrderAreRejected() {
        Auth auth = accessToken("mfa-guard");
        Response confirm = call(auth, "/auth/mfa/bind/confirm", Map.of("code", "123456"));
        assertThat(confirm.status()).isEqualTo(400);
        assertThat(confirm.code()).isEqualTo("mfa.bindingExpired");
        for (String path : List.of("/auth/mfa/recovery-codes/regenerate", "/auth/mfa/unbind")) {
            Response response = call(auth, path, Map.of("code", "123456"));
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("mfa.notBound");
        }
        assertThat(call(auth, "/auth/mfa/unbind", Map.of("code", "12345")).code())
                .isEqualTo("request.invalid");
        assertThat(mfaAudits(GUARD)).isEmpty();
    }

    @Test
    void passwordLoginOfABoundIdentityRequiresMfaAndTheAuthorizationRemembersIt() {
        Bound bound = bind("mfa-login");
        Login first = passwordLogin("mfa-login", null);
        assertThat(first.response().status()).isEqualTo(200);
        assertThat(first.response().data()).containsEntry("nextStep", "mfa");
        // 完成 MFA 之前不返回可进入的租户，也不能发起授权码流程。
        assertThat(first.response().data().get("memberships")).isNull();
        assertThat(authorizeStatus(first.cookie())).isIn(401, 403);

        // 重放绑定时实际提交的码，避免跨过时间步后重新生成的是新码；失败后待 MFA 状态保留，可以重试。
        Login replayed = submitMfa(first.cookie(), Map.of("code", bound.bindingCode()));
        assertThat(replayed.response().status()).isEqualTo(400);
        assertThat(replayed.response().code()).isEqualTo("mfa.codeInvalid");
        Login passed = submitMfa(first.cookie(), Map.of("code", nextCode(bound.secret())));
        assertThat(passed.response().status()).isEqualTo(200);
        assertThat(passed.response().data())
                .containsEntry("nextStep", "authorize")
                .containsEntry("clientId", "tenant-app")
                .containsEntry("redirectUri", "http://localhost:5174/");
        assertThat((List<?>) passed.response().data().get("memberships")).hasSize(2);
        // 完成 MFA 时换发会话标识。
        assertThat(passed.cookie()).isNotEqualTo(first.cookie());

        Map<?, ?> tokens = exchange(authorizationCode(passed.cookie()), LOGIN_IN_DEMO_TENANT);
        assertThat(mfaCompleted((String) tokens.get("refreshToken"))).isTrue();
        // Refresh Token 链沿用建立授权时的认证结果，refresh 与 switch 之后仍可读到。
        Map<?, ?> refreshed = tokenCall("/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken")));
        assertThat(mfaCompleted((String) refreshed.get("refreshToken"))).isTrue();
        Map<?, ?> switched = tokenCall(
                "/auth/switch",
                Map.of("refreshToken", refreshed.get("refreshToken"), "membershipId", LOGIN_IN_SECOND_TENANT));
        assertThat(switched.get("tenantId")).isEqualTo(SECOND_TENANT.toString());
        assertThat(mfaCompleted((String) switched.get("refreshToken"))).isTrue();

        // 完成后待 MFA 状态即清除，同一会话不能再次提交。
        assertThat(submitMfa(
                                passed.cookie(),
                                Map.of("recoveryCode", bound.recoveryCodes().get(0)))
                        .response()
                        .code())
                .isEqualTo("auth.mfaExpired");
        assertThat(loginMfaAudits(LOGIN)).containsExactly("totp:mfa.codeInvalid", "totp:success");
    }

    @Test
    void codeLoginOfABoundIdentityAlsoRequiresMfa() {
        Bound bound = bind("mfa-code-login");
        String email = "mfa-code-login@example.com";
        assertThat(post("/auth/login/send-code", Map.of("target", email), null)
                        .response()
                        .status())
                .isEqualTo(200);
        Login first = post("/auth/code-login", Map.of("target", email, "code", SENT_CODES.get(email)), null);
        assertThat(first.response().status()).isEqualTo(200);
        assertThat(first.response().data()).containsEntry("nextStep", "mfa");
        assertThat(authorizeStatus(first.cookie())).isIn(401, 403);

        Login passed = submitMfa(first.cookie(), Map.of("code", nextCode(bound.secret())));
        assertThat(passed.response().data()).containsEntry("nextStep", "authorize");
        assertThat((List<?>) passed.response().data().get("memberships")).hasSize(1);
        Map<?, ?> tokens = exchange(authorizationCode(passed.cookie()), null);
        assertThat(mfaCompleted((String) tokens.get("refreshToken"))).isTrue();
        assertThat(loginMfaAudits(CODE_LOGIN)).containsExactly("totp:success");
    }

    @Test
    void aRecoveryCodeCanReplaceTheCodeOnlyOnce() {
        Bound bound = bind("mfa-recovery");
        String code = bound.recoveryCodes().get(0);
        Login first = passwordLogin("mfa-recovery", null);
        // 抄写时的大小写、空格与连字符不影响匹配。
        Login passed = submitMfa(
                first.cookie(),
                Map.of("recoveryCode", code.toLowerCase(Locale.ROOT).replace('-', ' ')));
        assertThat(passed.response().data()).containsEntry("nextStep", "authorize");
        assertThat(probe.activeRecoveryCodeHashes(RECOVERY)).hasSize(9);
        assertThat(mfaCompleted((String)
                        exchange(authorizationCode(passed.cookie()), null).get("refreshToken")))
                .isTrue();

        Login again = passwordLogin("mfa-recovery", null);
        Login reused = submitMfa(again.cookie(), Map.of("recoveryCode", code));
        assertThat(reused.response().status()).isEqualTo(400);
        assertThat(reused.response().code()).isEqualTo("mfa.recoveryCodeInvalid");
        assertThat(submitMfa(
                                again.cookie(),
                                Map.of("recoveryCode", bound.recoveryCodes().get(1)))
                        .response()
                        .data())
                .containsEntry("nextStep", "authorize");
        assertThat(probe.activeRecoveryCodeHashes(RECOVERY)).hasSize(8);

        // 动态码与 Recovery Code 须恰好提供一个。
        Login third = passwordLogin("mfa-recovery", null);
        assertThat(submitMfa(third.cookie(), Map.of()).response().code()).isEqualTo("request.invalid");
        assertThat(submitMfa(
                                third.cookie(),
                                Map.of(
                                        "code",
                                        nextCode(bound.secret()),
                                        "recoveryCode",
                                        bound.recoveryCodes().get(2)))
                        .response()
                        .code())
                .isEqualTo("request.invalid");
        assertThat(loginMfaAudits(RECOVERY))
                .containsExactly(
                        "recoveryCode:success", "recoveryCode:mfa.recoveryCodeInvalid", "recoveryCode:success");
    }

    @Test
    void anExpiredPendingStateRequiresTheFirstFactorAgain() {
        Bound bound = bind("mfa-expired");
        Login first = passwordLogin("mfa-expired", null);
        expirePendingMfa(first.cookie());
        Login expired = submitMfa(first.cookie(), Map.of("code", nextCode(bound.secret())));
        assertThat(expired.response().status()).isEqualTo(401);
        assertThat(expired.response().code()).isEqualTo("auth.mfaExpired");
        assertThat(authorizeStatus(expired.cookie())).isIn(401, 403);

        // 过期时不校验、不消耗动态码；重新提交 First Factor 后即可完成。
        Login again = passwordLogin("mfa-expired", null);
        assertThat(submitMfa(again.cookie(), Map.of("code", nextCode(bound.secret())))
                        .response()
                        .data())
                .containsEntry("nextStep", "authorize");
        assertThat(loginMfaAudits(EXPIRED)).containsExactly("totp:auth.mfaExpired", "totp:success");
    }

    @Test
    void aPendingSessionCannotObtainTokensEvenIfItWasSignedInBefore() {
        bind("mfa-pending");
        // 已登录的会话再以已绑定身份通过 First Factor：原有的已认证主体被清除，不能借它继续授权。
        Login signedIn = passwordLogin("mfa-unbound", null);
        assertThat(authorizeStatus(signedIn.cookie())).isEqualTo(302);
        Login pending = passwordLogin("mfa-pending", signedIn.cookie());
        assertThat(pending.response().data()).containsEntry("nextStep", "mfa");
        assertThat(authorizeStatus(pending.cookie())).isIn(401, 403);
        assertThat(authorizeStatus(signedIn.cookie())).isIn(401, 403);

        // 没有待 MFA 状态：无会话，或会话已认证。
        assertThat(submitMfa(null, Map.of("code", "123456")).response().code()).isEqualTo("auth.mfaExpired");
        Login unbound = passwordLogin("mfa-unbound", null);
        assertThat(submitMfa(unbound.cookie(), Map.of("code", "123456"))
                        .response()
                        .code())
                .isEqualTo("auth.mfaExpired");
    }

    @Test
    void anIdentityWithoutAnAuthenticatorSignsInAsBefore() {
        Login login = passwordLogin("mfa-unbound", null);
        assertThat(login.response().data())
                .containsEntry("nextStep", "authorize")
                .containsEntry("clientId", "tenant-app");
        assertThat((List<?>) login.response().data().get("memberships")).hasSize(1);
        // 未绑定时授权上记为未完成 MFA（ADR-0019 的「未绑定、无需 MFA」）。
        assertThat(mfaCompleted((String)
                        exchange(authorizationCode(login.cookie()), null).get("refreshToken")))
                .isFalse();
        assertThat(loginMfaAudits(UNBOUND)).isEmpty();
    }

    /** 开始绑定并从 otpauth URI 中取出密钥，模拟验证器 App 扫码。 */
    private byte[] start(Auth auth) {
        Response started = call(auth, "/auth/mfa/bind/start", null);
        assertThat(started.status()).isEqualTo(200);
        return secretOf((String) started.data().get("otpauthUri"));
    }

    /** 该身份产出的 MFA 审计动作，按发生顺序；Authenticator 是全局凭证，事件不属于任何租户。 */
    private List<String> mfaAudits(UUID identityId) {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event ->
                        identityId.equals(event.actorId()) && event.action().startsWith("mfa."))
                .peek(event -> {
                    assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
                    assertThat(event.tenantId()).isNull();
                    assertThat(event.resourceId()).isEqualTo(identityId.toString());
                })
                .map(AuditEvent::action)
                .toList();
    }

    record Auth(String bearer, String cookie) {}

    record Response(int status, Map<?, ?> body) {
        String code() {
            return (String) body.get("code");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }

    private static List<String> recoveryCodes(Response response) {
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) response.data().get("recoveryCodes");
        return codes;
    }

    private static String currentCode(byte[] secret) {
        return Totp.code(secret, Totp.step(Instant.now()));
    }

    private static String nextCode(byte[] secret) {
        return Totp.code(secret, Totp.step(Instant.now()) + 1);
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
            if (!valid.contains(code)) {
                return code;
            }
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

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private Response call(Auth auth, String path, Object body) {
        return RestClient.create(baseUrl())
                .method(HttpMethod.POST)
                .uri(path)
                .headers(headers -> {
                    if (auth != null && auth.bearer() != null) {
                        headers.setBearerAuth(auth.bearer());
                    }
                    if (auth != null && auth.cookie() != null) {
                        headers.add("Cookie", auth.cookie());
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? Map.of() : body)
                .exchange((req, res) -> {
                    assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
                    return new Response(res.getStatusCode().value(), res.bodyTo(Map.class));
                });
    }

    /** 账号安全页读取的绑定状态，只接受 Access Token。 */
    private boolean bound(Auth auth) {
        Map<?, ?> body = RestClient.create(baseUrl())
                .get()
                .uri("/auth/mfa")
                .headers(headers -> headers.setBearerAuth(auth.bearer()))
                .retrieve()
                .body(Map.class);
        return (Boolean) ((Map<?, ?>) body.get("data")).get("bound");
    }

    private Auth accessToken(String account) {
        String cookie = session("/auth/login", account, PASSWORD);
        var form = Map.of("code", authorizationCode(cookie), "codeVerifier", VERIFIER);
        Map<?, ?> tokens = (Map<?, ?>) RestClient.create(baseUrl())
                .post()
                .uri("/auth/exchange")
                .body(form)
                .retrieve()
                .body(Map.class)
                .get("data");
        return new Auth((String) tokens.get("accessToken"), null);
    }

    private Auth loginSession(String account) {
        return new Auth(null, session("/auth/login", account, PASSWORD));
    }

    private Auth operatorSession() {
        return new Auth(null, session("/platform/auth/login", "operator", "Operator-password-123!"));
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

    record Bound(byte[] secret, String bindingCode, List<String> recoveryCodes) {}

    record Login(Response response, String cookie) {}

    /** 尚未绑定的身份凭登录会话绑定 Authenticator，确认时用掉当前步长的动态码。 */
    private Bound bind(String account) {
        Auth session = loginSession(account);
        byte[] secret = start(session);
        String bindingCode = currentCode(secret);
        Response confirmed = call(session, "/auth/mfa/bind/confirm", Map.of("code", bindingCode));
        assertThat(confirmed.status()).isEqualTo(201);
        return new Bound(secret, bindingCode, recoveryCodes(confirmed));
    }

    private Login passwordLogin(String account, String cookie) {
        return post("/auth/login", Map.of("account", account, "password", PASSWORD), cookie);
    }

    private Login submitMfa(String cookie, Map<String, ?> body) {
        return post("/auth/login/mfa", body, cookie);
    }

    /** 带上会话 Cookie 发送 JSON 请求；响应换发会话 Cookie 时返回新值，否则沿用原值。 */
    private Login post(String path, Object body, String cookie) {
        return noRedirectClient()
                .post()
                .uri(path)
                .headers(headers -> {
                    if (cookie != null) {
                        headers.add("Cookie", cookie);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
                    String issued = res.getHeaders().getFirst("Set-Cookie");
                    return new Login(
                            new Response(res.getStatusCode().value(), res.bodyTo(Map.class)),
                            issued == null ? cookie : issued.split(";")[0]);
                });
    }

    private Map<?, ?> exchange(String code, UUID membershipId) {
        var form = new HashMap<String, Object>(Map.of("code", code, "codeVerifier", VERIFIER));
        if (membershipId != null) {
            form.put("membershipId", membershipId);
        }
        return tokenCall("/auth/exchange", form);
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

    /** 刷新凭证所属授权上是否记为已完成 MFA；Token 定制器在 refresh 与 switch 时读取的正是这个主体。 */
    private boolean mfaCompleted(String refreshToken) {
        OAuth2Authorization authorization = authorizations.findByToken(refreshToken, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(authorization).isNotNull();
        Authentication principal = authorization.getAttribute(Principal.class.getName());
        return MfaAuthority.completed(principal);
    }

    /** 把会话中的待 MFA 状态改为已过期，模拟超过有效期。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void expirePendingMfa(String cookie) {
        // 会话 Cookie 的值是 Base64 编码的会话标识。
        String id = new String(
                Base64.getDecoder().decode(cookie.substring(cookie.indexOf('=') + 1)), StandardCharsets.UTF_8);
        SessionRepository repository = httpSessions;
        Session session = repository.findById(id);
        PendingMfa pending = session.getAttribute(PendingMfa.SESSION_ATTRIBUTE);
        session.setAttribute(
                PendingMfa.SESSION_ATTRIBUTE,
                new PendingMfa(pending.identityId(), Instant.now().minusSeconds(1)));
        repository.save(session);
    }

    /** 该身份的 auth.mfa 事件，按发生顺序记为「方式:success」或「方式:错误码」；发生在登录会话上，不属于任何租户。 */
    private List<String> loginMfaAudits(UUID identityId) {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event ->
                        identityId.equals(event.actorId()) && event.action().equals("auth.mfa"))
                .peek(event -> {
                    assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
                    assertThat(event.tenantId()).isNull();
                })
                .map(event -> event.detail().get("method") + ":"
                        + (event.outcome() == AuditOutcome.SUCCESS ? "success" : event.errorCode()))
                .toList();
    }

    private int authorizeStatus(String cookie) {
        return authorizeRequest(cookie)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private String authorizationCode(String cookie) {
        var response = authorizeRequest(cookie).retrieve().toBodilessEntity();
        return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private RestClient.RequestHeadersSpec<?> authorizeRequest(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            return noRedirectClient()
                    .get()
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

    private RestClient noRedirectClient() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }
}
