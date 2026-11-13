package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.PlatformMfaClient.Login;
import io.saasforge.iam.PlatformMfaClient.Response;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * MFA Reset 与身份查询（账号安全工单 05）：持有 {@code platform:identity-mfa:reset} 的 Operator 按登录标识精确查找 Identity
 * 并重置其 MFA，Super Admin 重置 Operator 的 MFA；重置后下次登录不再要求 MFA。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql({"/fixtures/mfa_operators.sql", "/fixtures/mfa_reset.sql"})
class MfaResetTest {
    static final String IDENTITY_PASSWORD = "Demo-password-123!";
    static final UUID LOOKUP = UUID.fromString("019a0110-0000-7000-8000-000000000501");
    static final UUID TARGET = UUID.fromString("019a0110-0000-7000-8000-000000000502");
    static final UUID HOLDER = UUID.fromString("019a0110-0000-7000-8000-000000000521");
    static final UUID TARGET_OPERATOR = UUID.fromString("019a0110-0000-7000-8000-000000000523");

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
        registry.add("forge.iam.signing-key-file", () -> "target/test-mfa-reset-signing.jwk");
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
    void lookupFindsAnIdentityByAccountEmailOrPhoneExactly() {
        String holder = operatorToken("op-reset-holder");
        for (String identifier : new String[] {"reset-lookup", "RESET-LOOKUP@example.com", "13900000501"}) {
            Response found = call(holder, "/platform/identities/lookup", Map.of("identifier", identifier));
            assertThat(found.status()).as(identifier).isEqualTo(200);
            assertThat(found.data())
                    .as(identifier)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("identityId", LOOKUP.toString(), "displayName", "查找身份", "mfaBound", false));
        }

        // 只做精确匹配：前缀或不存在的标识都查不到。
        for (String identifier : new String[] {"reset-look", "nobody@example.com"}) {
            Response missing = call(holder, "/platform/identities/lookup", Map.of("identifier", identifier));
            assertThat(missing.status()).as(identifier).isEqualTo(404);
            assertThat(missing.code()).as(identifier).isEqualTo("mfa.identityNotFound");
            assertThat(missing.body().get("userTip")).as(identifier).isEqualTo("未找到该登录标识对应的身份");
        }
    }

    @Test
    void resettingAnIdentityClearsItsMfaSoTheNextLoginNoLongerAsksForIt() {
        String holder = operatorToken("op-reset-holder");
        byte[] secret = bindIdentity("reset-target");
        assertThat(identityLogin("reset-target").response().data()).containsEntry("nextStep", "mfa");
        assertThat(call(holder, "/platform/identities/lookup", Map.of("identifier", "reset-target"))
                        .data())
                .containsEntry("mfaBound", true);

        Response reset = call(holder, "/platform/identities/" + TARGET + "/mfa/reset", null);
        assertThat(reset.status()).isEqualTo(200);
        assertThat(identityLogin("reset-target").response().data()).containsEntry("nextStep", "authorize");
        assertThat(call(holder, "/platform/identities/lookup", Map.of("identifier", "reset-target"))
                        .data())
                .containsEntry("mfaBound", false);
        // 旧 Authenticator 与 Recovery Code 都已清除，可以重新绑定。
        assertThat(bindIdentity("reset-target")).isNotEqualTo(secret);

        assertThat(resetAudits()).contains("identity:" + TARGET + ":" + HOLDER);
        Response missing = call(holder, "/platform/identities/" + UUID.randomUUID() + "/mfa/reset", null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("mfa.identityNotFound");
        Response unbound = call(holder, "/platform/identities/" + LOOKUP + "/mfa/reset", null);
        assertThat(unbound.status()).isEqualTo(409);
        assertThat(unbound.code()).isEqualTo("mfa.notBound");
    }

    @Test
    void onlyASuperAdminCanResetAnOperatorsMfa() {
        String token = operatorToken("op-reset-target");
        String uri = (String) http.withToken(token, "/platform/auth/mfa/bind/start", null)
                .data()
                .get("otpauthUri");
        assertThat(http.withToken(
                                token,
                                "/platform/auth/mfa/bind/confirm",
                                Map.of("code", PlatformMfaClient.code(PlatformMfaClient.secretOf(uri), 0)))
                        .status())
                .isEqualTo(201);
        assertThat(http.login("op-reset-target").response().data()).containsEntry("nextStep", "mfa");

        // 持有身份 MFA 重置权限码也不能重置 Operator。
        Response denied = call(operatorToken("op-reset-holder"), operatorReset(TARGET_OPERATOR), null);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("auth.forbidden");

        String superAdmin = operatorToken("operator");
        assertThat(call(superAdmin, operatorReset(TARGET_OPERATOR), null).status())
                .isEqualTo(200);
        assertThat(http.login("op-reset-target").response().data()).containsEntry("nextStep", "authorize");
        assertThat(resetAudits()).contains("operator:" + TARGET_OPERATOR + ":" + SUPER_ADMIN);
        assertThat(call(superAdmin, operatorReset(TARGET_OPERATOR), null).code())
                .isEqualTo("mfa.notBound");
        assertThat(call(superAdmin, operatorReset(UUID.randomUUID()), null).status())
                .isEqualTo(404);
    }

    @Test
    void anOperatorWithoutThePermissionIsForbidden() {
        String plain = operatorToken("op-reset-plain");
        Response lookup = call(plain, "/platform/identities/lookup", Map.of("identifier", "reset-lookup"));
        assertThat(lookup.status()).isEqualTo(403);
        assertThat(lookup.code()).isEqualTo("auth.forbidden");
        assertThat(call(plain, "/platform/identities/" + LOOKUP + "/mfa/reset", null)
                        .status())
                .isEqualTo(403);
        assertThat(call(plain, operatorReset(TARGET_OPERATOR), null).status()).isEqualTo(403);
    }

    @Test
    void tenantTokensCannotReachTheResetEndpoints() {
        // 租户侧没有重置他人 MFA 的接口；平台接口只接受 Platform Realm 的 Access Token。
        String tenantToken = tenantAccessToken("reset-lookup");
        for (String path : new String[] {
            "/platform/identities/lookup",
            "/platform/identities/" + LOOKUP + "/mfa/reset",
            operatorReset(TARGET_OPERATOR)
        }) {
            assertThat(call(tenantToken, path, Map.of("identifier", "reset-lookup"))
                            .status())
                    .as(path)
                    .isEqualTo(401);
        }
    }

    private static String operatorReset(UUID operatorId) {
        return "/platform/operators/" + operatorId + "/mfa/reset";
    }

    /** mfa.reset 事件，记为「资源类型:资源标识:操作人」；均属 Platform Realm、不带租户。 */
    private List<String> resetAudits() {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("mfa.reset"))
                .peek(event -> {
                    assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
                    assertThat(event.tenantId()).isNull();
                })
                .map(event -> event.resourceType() + ":" + event.resourceId() + ":" + event.actorId())
                .toList();
    }

    private Login identityLogin(String account) {
        return http.post("/auth/login", Map.of("account", account, "password", IDENTITY_PASSWORD), null, null);
    }

    /** 未绑定的身份凭登录会话绑定 Authenticator，返回密钥。 */
    private byte[] bindIdentity(String account) {
        String cookie = identityLogin(account).cookie();
        Response started = http.post("/auth/mfa/bind/start", null, cookie, null).response();
        assertThat(started.status()).isEqualTo(200);
        byte[] secret = PlatformMfaClient.secretOf((String) started.data().get("otpauthUri"));
        assertThat(http.post("/auth/mfa/bind/confirm", Map.of("code", PlatformMfaClient.code(secret, 0)), cookie, null)
                        .response()
                        .status())
                .isEqualTo(201);
        return secret;
    }

    private String tenantAccessToken(String account) {
        String cookie = identityLogin(account).cookie();
        String authorize = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build()
                .get()
                .uri(builder -> builder.path("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "tenant-app")
                        .queryParam("redirect_uri", "http://localhost:5174/")
                        .queryParam("scope", "tenant")
                        .queryParam("state", "test-state")
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("code_challenge", challenge())
                        .build())
                .header("Cookie", cookie)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation()
                .toString();
        String code = UriComponentsBuilder.fromUriString(authorize)
                .build()
                .getQueryParams()
                .getFirst("code");
        Map<String, Object> tokens = http.post(
                        "/auth/exchange", Map.of("code", code, "codeVerifier", PlatformMfaClient.VERIFIER), null, null)
                .response()
                .data();
        return (String) tokens.get("accessToken");
    }

    private static String challenge() {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(PlatformMfaClient.VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** 带 Bearer Token 调用平台管理接口；管理接口不在 /platform/auth/ 下，缓存头由 Spring Security 默认值决定，不在此断言。 */
    private Response call(String bearer, String path, Object body) {
        return RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri(path)
                .headers(headers -> {
                    if (bearer != null) {
                        headers.setBearerAuth(bearer);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? Map.of() : body)
                .exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    private String operatorToken(String account) {
        return http.accessToken(http.login(account).cookie());
    }
}
