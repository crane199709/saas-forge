package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.PlatformMfaClient.Login;
import io.saasforge.iam.PlatformMfaClient.Response;
import io.saasforge.iam.mfa.mapper.MfaProbeMapper;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
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
 * {@code forge.iam.platform.mfa-required=true}（账号安全工单 04）：未绑定的 Operator 在登录中当场绑定后进入，不需要管理员介入；
 * 已绑定者不能解绑，只能先验证旧动态码再更换。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "forge.iam.platform.mfa-required=true"})
@Sql("/fixtures/mfa_operators.sql")
class RequiredOperatorMfaTest {
    static final UUID FORCED = UUID.fromString("019a0110-0000-7000-8000-000000000405");
    static final UUID REPLACE = UUID.fromString("019a0110-0000-7000-8000-000000000407");

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
        registry.add("forge.iam.signing-key-file", () -> "target/test-required-operator-mfa-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    MfaProbeMapper probe;

    PlatformMfaClient http;

    @BeforeEach
    void client() {
        http = new PlatformMfaClient(port, outbox);
    }

    @Test
    void anUnboundOperatorBindsDuringLoginAndIsThenSignedIn() {
        Login first = http.login("op-mfa-forced");
        assertThat(first.response().status()).isEqualTo(200);
        assertThat(first.response().data()).containsEntry("nextStep", "bind").containsEntry("clientId", null);
        // 必须绑定时不能发起授权码流程，也不能走待 MFA 的提交接口。
        assertThat(http.authorizeStatus(first.cookie())).isIn(401, 403);
        assertThat(http.post("/platform/auth/login/mfa", Map.of("code", "123456"), first.cookie(), null)
                        .response()
                        .code())
                .isEqualTo("auth.mfaExpired");

        Login started = http.post("/platform/auth/login/bind/start", null, first.cookie(), null);
        assertThat(started.response().status()).isEqualTo(200);
        byte[] secret =
                PlatformMfaClient.secretOf((String) started.response().data().get("otpauthUri"));
        Login wrong = http.post(
                "/platform/auth/login/bind/confirm",
                Map.of("code", PlatformMfaClient.wrongCode(secret)),
                started.cookie(),
                null);
        assertThat(wrong.response().status()).isEqualTo(400);
        assertThat(wrong.response().code()).isEqualTo("mfa.codeInvalid");
        assertThat(http.authorizeStatus(wrong.cookie())).isIn(401, 403);

        Login bound = http.post(
                "/platform/auth/login/bind/confirm",
                Map.of("code", PlatformMfaClient.code(secret, 0)),
                wrong.cookie(),
                null);
        assertThat(bound.response().status()).isEqualTo(201);
        assertThat(bound.response().data())
                .containsEntry("nextStep", "authorize")
                .containsEntry("clientId", "platform-console");
        assertThat(bound.response().recoveryCodes()).hasSize(10);
        // 登录流程没有认证上下文，标准列操作人为系统。
        assertThat(probe.operatorAuthenticatorCreatedBy(FORCED)).isNull();
        assertThat(bound.cookie()).isNotEqualTo(first.cookie());
        Map<?, ?> tokens = http.tokens(bound.cookie());
        assertThat(tokens.get("operatorName")).isEqualTo("op-mfa-forced");
        // 授权记为已完成 MFA，刷新时仍可换发。
        assertThat(http.post("/platform/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null, null)
                        .response()
                        .status())
                .isEqualTo(200);

        // 之后的登录走待 MFA。
        assertThat(http.login("op-mfa-forced").response().data()).containsEntry("nextStep", "mfa");
        assertThat(http.audits(FORCED)).containsExactly("mfa.bind");
    }

    @Test
    void aBoundOperatorCannotUnbindButCanReplaceTheAuthenticator() {
        byte[] old = forcedBind("op-mfa-replace", -1);
        Login login = http.login("op-mfa-replace");
        assertThat(login.response().data()).containsEntry("nextStep", "mfa");
        String token = http.accessToken(http.post(
                        "/platform/auth/login/mfa",
                        Map.of("code", PlatformMfaClient.code(old, 0)),
                        login.cookie(),
                        null)
                .cookie());

        // 解绑在校验动态码之前即被拒绝，不消耗动态码。
        Response unbind =
                http.withToken(token, "/platform/auth/mfa/unbind", Map.of("code", PlatformMfaClient.code(old, 1)));
        assertThat(unbind.status()).isEqualTo(403);
        assertThat(unbind.code()).isEqualTo("mfa.unbindForbidden");
        assertThat(http.withToken(token, "/platform/auth/mfa/bind/start", null).code())
                .isEqualTo("mfa.alreadyBound");
        assertThat(http.withToken(
                                token,
                                "/platform/auth/mfa/replace/start",
                                Map.of("code", PlatformMfaClient.wrongCode(old)))
                        .code())
                .isEqualTo("mfa.codeInvalid");

        Response started = http.withToken(
                token, "/platform/auth/mfa/replace/start", Map.of("code", PlatformMfaClient.code(old, 1)));
        assertThat(started.status()).isEqualTo(200);
        byte[] replacement = PlatformMfaClient.secretOf((String) started.data().get("otpauthUri"));
        Response confirmed = http.withToken(
                token, "/platform/auth/mfa/bind/confirm", Map.of("code", PlatformMfaClient.code(replacement, 0)));
        assertThat(confirmed.status()).isEqualTo(201);
        assertThat(confirmed.recoveryCodes()).hasSize(10);

        // 旧 Authenticator 作废，新的生效。
        Login next = http.login("op-mfa-replace");
        assertThat(http.post(
                                "/platform/auth/login/mfa",
                                Map.of("code", PlatformMfaClient.code(old, 1)),
                                next.cookie(),
                                null)
                        .response()
                        .code())
                .isEqualTo("mfa.codeInvalid");
        assertThat(http.post(
                                "/platform/auth/login/mfa",
                                Map.of("code", PlatformMfaClient.code(replacement, 1)),
                                next.cookie(),
                                null)
                        .response()
                        .data())
                .containsEntry("nextStep", "authorize");
        assertThat(http.audits(REPLACE))
                .containsExactly(
                        "mfa.bind",
                        "auth.mfa:totp:success",
                        "mfa.bind",
                        "auth.mfa:totp:mfa.codeInvalid",
                        "auth.mfa:totp:success");
    }

    @Test
    void aReplacementCannotBeConfirmedWithoutOneStarted() {
        byte[] secret = forcedBind("op-mfa-keep", -1);
        Login login = http.login("op-mfa-keep");
        String token = http.accessToken(http.post(
                        "/platform/auth/login/mfa",
                        Map.of("code", PlatformMfaClient.code(secret, 0)),
                        login.cookie(),
                        null)
                .cookie());
        Response confirmed = http.withToken(
                token, "/platform/auth/mfa/bind/confirm", Map.of("code", PlatformMfaClient.code(secret, 1)));
        assertThat(confirmed.status()).isEqualTo(400);
        assertThat(confirmed.code()).isEqualTo("mfa.bindingExpired");
        // 已绑定者登录时是待 MFA，不能借登录中绑定的接口另绑一个。
        assertThat(http.post(
                                "/platform/auth/login/bind/start",
                                null,
                                http.login("op-mfa-keep").cookie(),
                                null)
                        .response()
                        .code())
                .isEqualTo("auth.mfaExpired");
    }

    /** 未绑定的 Operator 在登录中当场绑定，确认时用掉相对当前步长偏移 {@code offset} 的动态码；返回密钥。 */
    private byte[] forcedBind(String account, int offset) {
        Login login = http.login(account);
        assertThat(login.response().data()).containsEntry("nextStep", "bind");
        Login started = http.post("/platform/auth/login/bind/start", null, login.cookie(), null);
        byte[] secret =
                PlatformMfaClient.secretOf((String) started.response().data().get("otpauthUri"));
        Login bound = http.post(
                "/platform/auth/login/bind/confirm",
                Map.of("code", PlatformMfaClient.code(secret, offset)),
                started.cookie(),
                null);
        assertThat(bound.response().status()).isEqualTo(201);
        return secret;
    }
}
