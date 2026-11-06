package io.saasforge.iam;

import static io.saasforge.iam.PlatformMfaClient.code;
import static io.saasforge.iam.PlatformMfaClient.secretOf;
import static io.saasforge.iam.PlatformMfaClient.wrongCode;
import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.PlatformMfaClient.Login;
import io.saasforge.iam.PlatformMfaClient.Response;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * Platform Realm 的 MFA（账号安全工单 04），{@code forge.iam.platform.mfa-required} 取默认值 false：未绑定的 Operator 登录不变；
 * 自助绑定后登录须完成 MFA，可解绑、可重新生成 Recovery Code。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/mfa_operators.sql")
class OperatorMfaTest {
    static final UUID UNBOUND = UUID.fromString("019a0110-0000-7000-8000-000000000401");
    static final UUID LOGIN = UUID.fromString("019a0110-0000-7000-8000-000000000402");
    static final UUID RECOVERY = UUID.fromString("019a0110-0000-7000-8000-000000000403");
    static final UUID UNBIND = UUID.fromString("019a0110-0000-7000-8000-000000000404");

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
        registry.add("forge.iam.signing-key-file", () -> "target/test-operator-mfa-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    PlatformMfaClient http;

    @BeforeEach
    void client() {
        http = new PlatformMfaClient(port, outbox);
    }

    @Test
    void anOperatorWithoutAnAuthenticatorSignsInAsBefore() {
        Login login = http.login("op-mfa-unbound");
        assertThat(login.response().status()).isEqualTo(200);
        assertThat(login.response().data())
                .containsEntry("nextStep", "authorize")
                .containsEntry("clientId", "platform-console")
                .containsEntry("redirectUri", "http://localhost:5173/");
        assertThat(http.tokens(login.cookie()).get("operatorName")).isEqualTo("op-mfa-unbound");
        assertThat(http.audits(UNBOUND)).isEmpty();
    }

    @Test
    void aBoundOperatorMustCompleteMfaBeforeAuthorizing() {
        byte[] secret = bindWithToken("op-mfa-login").secret();
        Login first = http.login("op-mfa-login");
        assertThat(first.response().status()).isEqualTo(200);
        assertThat(first.response().data()).containsEntry("nextStep", "mfa").containsEntry("clientId", null);
        assertThat(http.authorizeStatus(first.cookie())).isIn(401, 403);
        // 待 MFA 时不能借登录中绑定的接口跳过 MFA。
        assertThat(http.post("/platform/auth/login/bind/start", null, first.cookie(), null)
                        .response()
                        .code())
                .isEqualTo("auth.mfaExpired");

        // 绑定时用掉的动态码不能再用于登录；失败后待 MFA 状态保留，可以重试。
        Login replayed = submitMfa(first.cookie(), Map.of("code", code(secret, 0)));
        assertThat(replayed.response().status()).isEqualTo(400);
        assertThat(replayed.response().code()).isEqualTo("mfa.codeInvalid");
        Login passed = submitMfa(first.cookie(), Map.of("code", code(secret, 1)));
        assertThat(passed.response().status()).isEqualTo(200);
        assertThat(passed.response().data())
                .containsEntry("nextStep", "authorize")
                .containsEntry("clientId", "platform-console");
        assertThat(passed.cookie()).isNotEqualTo(first.cookie());
        assertThat(http.tokens(passed.cookie()).get("operatorName")).isEqualTo("op-mfa-login");

        assertThat(submitMfa(passed.cookie(), Map.of("code", code(secret, 1)))
                        .response()
                        .code())
                .isEqualTo("auth.mfaExpired");
        assertThat(http.audits(LOGIN))
                .containsExactly("mfa.bind", "auth.mfa:totp:mfa.codeInvalid", "auth.mfa:totp:success");
    }

    @Test
    void aRecoveryCodeCanReplaceTheCodeOnlyOnce() {
        Bound bound = bindWithToken("op-mfa-recovery");
        String recoveryCode = bound.recoveryCodes().get(0);
        Login first = http.login("op-mfa-recovery");
        Login passed = submitMfa(first.cookie(), Map.of("recoveryCode", recoveryCode));
        assertThat(passed.response().data()).containsEntry("nextStep", "authorize");

        Login again = http.login("op-mfa-recovery");
        Login reused = submitMfa(again.cookie(), Map.of("recoveryCode", recoveryCode));
        assertThat(reused.response().status()).isEqualTo(400);
        assertThat(reused.response().code()).isEqualTo("mfa.recoveryCodeInvalid");
        assertThat(submitMfa(again.cookie(), Map.of()).response().code()).isEqualTo("request.invalid");
        assertThat(http.audits(RECOVERY))
                .containsExactly(
                        "mfa.bind", "auth.mfa:recoveryCode:success", "auth.mfa:recoveryCode:mfa.recoveryCodeInvalid");
    }

    @Test
    void withoutTheRequirementAnOperatorCanRegenerateRecoveryCodesAndUnbind() {
        // 容差窗口只有前后各 1 步：绑定用掉上一步，登录改用 Recovery Code，留下当前步与下一步给重新生成与解绑。
        Bound bound = bindWithToken("op-mfa-unbind", -1);
        Login login = http.login("op-mfa-unbind");
        String token = http.accessToken(submitMfa(
                        login.cookie(),
                        Map.of("recoveryCode", bound.recoveryCodes().get(0)))
                .cookie());

        Response regenerated = http.withToken(
                token, "/platform/auth/mfa/recovery-codes/regenerate", Map.of("code", code(bound.secret(), 0)));
        assertThat(regenerated.status()).isEqualTo(201);
        assertThat(regenerated.recoveryCodes()).hasSize(10).doesNotContainAnyElementsOf(bound.recoveryCodes());

        Response wrong = http.withToken(token, "/platform/auth/mfa/unbind", Map.of("code", wrongCode(bound.secret())));
        assertThat(wrong.code()).isEqualTo("mfa.codeInvalid");
        Response unbound = http.withToken(token, "/platform/auth/mfa/unbind", Map.of("code", code(bound.secret(), 1)));
        assertThat(unbound.status()).isEqualTo(200);
        // 解绑后登录不再要求 MFA。
        assertThat(http.login("op-mfa-unbind").response().data()).containsEntry("nextStep", "authorize");
        assertThat(http.audits(UNBIND))
                .containsExactly(
                        "mfa.bind", "auth.mfa:recoveryCode:success", "mfa.recoveryCodesRegenerate", "mfa.unbind");
    }

    @Test
    void selfServiceEndpointsNeedAPlatformAccessToken() {
        Login session = http.login("op-mfa-unbound");
        for (String path : List.of(
                "/platform/auth/mfa/bind/start",
                "/platform/auth/mfa/bind/confirm",
                "/platform/auth/mfa/replace/start",
                "/platform/auth/mfa/recovery-codes/regenerate",
                "/platform/auth/mfa/unbind")) {
            assertThat(http.post(path, Map.of("code", "123456"), session.cookie(), null)
                            .response()
                            .status())
                    .as(path)
                    .isEqualTo(401);
        }
    }

    record Bound(byte[] secret, List<String> recoveryCodes) {}

    /** 未绑定的 Operator 凭 Platform Access Token 自助绑定，确认时用掉当前步长的动态码。 */
    private Bound bindWithToken(String account) {
        return bindWithToken(account, 0);
    }

    /** 同上，确认时用掉相对当前步长偏移 {@code offset} 的动态码。 */
    private Bound bindWithToken(String account, int offset) {
        String token = http.accessToken(http.login(account).cookie());
        Response started = http.withToken(token, "/platform/auth/mfa/bind/start", null);
        assertThat(started.status()).isEqualTo(200);
        String uri = (String) started.data().get("otpauthUri");
        assertThat(uri).startsWith("otpauth://totp/SaaS%20Forge:" + account + "?secret=");
        byte[] secret = secretOf(uri);
        Response confirmed =
                http.withToken(token, "/platform/auth/mfa/bind/confirm", Map.of("code", code(secret, offset)));
        assertThat(confirmed.status()).isEqualTo(201);
        assertThat(confirmed.recoveryCodes()).hasSize(10);
        return new Bound(secret, confirmed.recoveryCodes());
    }

    private Login submitMfa(String cookie, Map<String, ?> body) {
        return http.post("/platform/auth/login/mfa", body, cookie, null);
    }
}
