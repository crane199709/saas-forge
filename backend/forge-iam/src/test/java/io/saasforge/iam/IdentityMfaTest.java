package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.mfa.mapper.MfaProbeMapper;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Identity 的 Authenticator 与 Recovery Code（账号安全工单 01）：两步绑定、重新生成、解绑、防重放与审计。
 *
 * <p>每个用例使用 fixture 中的独立身份，绑定状态互不影响，用例顺序不影响结果。
 */
@Testcontainers
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
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-mfa-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Autowired
    MfaProbeMapper probe;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void bindingTakesEffectOnlyAfterTheFirstCodeAndReturnsRecoveryCodesOnce() {
        Auth auth = accessToken("mfa-bind");
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

        Response confirmed = call(auth, "/auth/mfa/bind/confirm", Map.of("code", currentCode(secret)));
        assertThat(confirmed.status()).isEqualTo(201);
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

        Map<?, ?> data() {
            return (Map<?, ?>) body.get("data");
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

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private Response call(Auth auth, String path, Object body) {
        return RestClient.create(baseUrl())
                .method(HttpMethod.POST)
                .uri(path)
                .headers(headers -> {
                    if (auth != null && auth.bearer() != null) headers.setBearerAuth(auth.bearer());
                    if (auth != null && auth.cookie() != null) headers.add("Cookie", auth.cookie());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? Map.of() : body)
                .exchange((req, res) -> {
                    assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
                    return new Response(res.getStatusCode().value(), res.bodyTo(Map.class));
                });
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

    private String authorizationCode(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var response = noRedirectClient()
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
                    .header("Cookie", cookie)
                    .retrieve()
                    .toBodilessEntity();
            return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                    .build()
                    .getQueryParams()
                    .getFirst("code");
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
