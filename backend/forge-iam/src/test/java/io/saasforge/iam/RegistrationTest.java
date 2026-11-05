package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.verification.service.ContactMessage;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.SmsSender;
import io.saasforge.iam.verification.service.VerificationMessage;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            // 有效期调短以便测试过期；其余用例在发送后立即使用验证码。
            "forge.iam.verification.code-ttl=4s"
        })
class RegistrationTest {
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
        registry.add("forge.iam.signing-key-file", () -> "target/test-signing.jwk");
    }

    /** 以 SPI 替换默认的日志实现，同时验证发送实现可插拔。 */
    @TestConfiguration
    static class CapturingSenders {
        @Bean
        Outbox outbox() {
            return new Outbox();
        }

        @Bean
        SmsSender smsSender(Outbox outbox) {
            return outbox::deliver;
        }

        @Bean
        EmailSender emailSender(Outbox outbox) {
            return outbox::deliver;
        }
    }

    static class Outbox {
        private final Map<String, String> latest = new ConcurrentHashMap<>();
        private final AtomicInteger count = new AtomicInteger();

        // 模拟发送通道故障：该目标的第一次发送失败。
        private final Map<String, Boolean> failOnce = new ConcurrentHashMap<>(Map.of("broken@example.com", true));

        void deliver(String target, ContactMessage message) {
            if (failOnce.remove(target) != null) throw new IllegalStateException("channel down");
            latest.put(target, ((VerificationMessage) message).code());
            count.incrementAndGet();
        }

        String code(String target) {
            return latest.get(target);
        }
    }

    @Autowired
    Outbox outbox;

    @LocalServerPort
    int port;

    private static final String PASSWORD = "Register-password-123!";
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @Test
    void registeredIdentityWithoutMembershipSignsInButHasNoTenant() {
        assertThat(post("/auth/register/send-code", Map.of("target", "new@example.com"))
                        .status())
                .isEqualTo(200);
        Result registered = post(
                "/auth/register",
                Map.of(
                        "account",
                        "newcomer",
                        "password",
                        PASSWORD,
                        "target",
                        "new@example.com",
                        "code",
                        outbox.code("new@example.com")));
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.body()).contains("\"identityId\":");

        Result login = post("/auth/login", Map.of("account", "newcomer", "password", PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body()).contains("\"memberships\":[]");
        // 同一邮箱也可以作为密码登录的标识。
        assertThat(post("/auth/login", Map.of("account", "NEW@example.com", "password", PASSWORD))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void phoneRegistrationCreatesIdentityWithoutEmail() {
        String phone = "13800000001";
        post("/auth/register/send-code", Map.of("target", phone));
        assertThat(register("phoneuser", phone, outbox.code(phone)).status()).isEqualTo(201);
        assertThat(post("/auth/login", Map.of("account", "phoneuser", "password", PASSWORD))
                        .body())
                .contains("\"memberships\":[]");
    }

    @Test
    @Sql("/fixtures/phone_identity.sql")
    void phoneCodeLoginWithoutMembershipLeadsToNoTenant() {
        String phone = "13900000001";
        assertThat(post("/auth/login/send-code", Map.of("target", phone)).status())
                .isEqualTo(200);
        Result login = post("/auth/code-login", Map.of("target", phone, "code", outbox.code(phone)));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body()).contains("\"memberships\":[]", "\"clientId\":\"tenant-app\"");
        // 验证码一次性有效。
        assertThat(post("/auth/code-login", Map.of("target", phone, "code", outbox.code(phone)))
                        .body())
                .contains("\"code\":\"verification.codeExpired\"");
    }

    @Test
    void codeLoginIssuesTheSameTokensAsPasswordLogin() {
        // 演示身份 demo@example.com 有一个 active Membership。
        post("/auth/login/send-code", Map.of("target", "DEMO@example.com"));
        Result login =
                post("/auth/code-login", Map.of("target", "demo@example.com", "code", outbox.code("DEMO@example.com")));
        assertThat(login.status()).isEqualTo(200);
        Jwt byCode = decode(tokensFor(login.cookie()));

        Jwt byPassword =
                decode(tokensFor(post("/auth/login", Map.of("account", "demo", "password", "Demo-password-123!"))
                        .cookie()));
        assertThat(byCode.getClaims().keySet())
                .containsExactlyInAnyOrderElementsOf(byPassword.getClaims().keySet());
        for (String claim : List.of("sub", "tenant_id", "membership_id", "ver", "iss", "aud")) {
            assertThat(byCode.getClaims().get(claim))
                    .isEqualTo(byPassword.getClaims().get(claim));
        }
    }

    @Test
    void duplicateAccountAndContactAreRejected() {
        // 演示数据已占用账号 demo 与邮箱 demo@example.com。
        assertThat(post("/auth/register/send-code", Map.of("target", "Demo@Example.com"))
                        .body())
                .contains("\"code\":\"registration.contactTaken\"", "\"httpStatus\":409");

        post("/auth/register/send-code", Map.of("target", "dup@example.com"));
        Result duplicateAccount = register("demo", "dup@example.com", outbox.code("dup@example.com"));
        assertThat(duplicateAccount.status()).isEqualTo(409);
        assertThat(duplicateAccount.body()).contains("\"code\":\"registration.accountTaken\"");
        // 账号重复不消费验证码，换个账号仍可完成注册；之后邮箱被占用。
        assertThat(register("dupuser", "dup@example.com", outbox.code("dup@example.com"))
                        .status())
                .isEqualTo(201);
        Result duplicateContact = register("another", "DUP@example.com", "000000");
        assertThat(duplicateContact.status()).isEqualTo(409);
        assertThat(duplicateContact.body()).contains("\"code\":\"registration.contactTaken\"");
    }

    @Test
    void expiredCodeIsRejected() throws InterruptedException {
        post("/auth/register/send-code", Map.of("target", "late@example.com"));
        String code = outbox.code("late@example.com");
        Thread.sleep(4500);
        Result late = register("lateuser", "late@example.com", code);
        assertThat(late.status()).isEqualTo(400);
        assertThat(late.body()).contains("\"code\":\"verification.codeExpired\"");
    }

    @Test
    void resendingWithinTheIntervalIsRejected() {
        assertThat(post("/auth/register/send-code", Map.of("target", "13800000002"))
                        .body())
                .contains("\"resendIn\":60");
        Result again = post("/auth/register/send-code", Map.of("target", "13800000002"));
        assertThat(again.status()).isEqualTo(429);
        assertThat(again.body()).contains("\"code\":\"verification.tooFrequent\"");
        // 发送频率按目标限制，不区分用途。
        assertThat(post("/auth/login/send-code", Map.of("target", "13800000002"))
                        .status())
                .isEqualTo(429);
    }

    @Test
    void codeIsInvalidatedAfterTooManyWrongAttempts() {
        post("/auth/register/send-code", Map.of("target", "guess@example.com"));
        String code = outbox.code("guess@example.com");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int attempt = 1; attempt < 5; attempt++) {
            assertThat(register("guesser", "guess@example.com", wrong).body())
                    .contains("\"code\":\"verification.codeInvalid\"");
        }
        assertThat(register("guesser", "guess@example.com", wrong).body())
                .contains("\"code\":\"verification.attemptsExceeded\"");
        // 达到上限后原验证码作废，正确的码也不再可用。
        assertThat(register("guesser", "guess@example.com", code).body())
                .contains("\"code\":\"verification.codeExpired\"");
    }

    @Test
    void failedDeliveryDoesNotHoldTheResendInterval() {
        assertThat(post("/auth/register/send-code", Map.of("target", "broken@example.com"))
                        .status())
                .isEqualTo(500);
        assertThat(post("/auth/register/send-code", Map.of("target", "broken@example.com"))
                        .status())
                .isEqualTo(200);
        assertThat(register("brokenuser", "broken@example.com", outbox.code("broken@example.com"))
                        .status())
                .isEqualTo(201);
    }

    @Test
    void loginCodeRequestDoesNotRevealWhetherTheTargetIsRegistered() {
        int before = outbox.count.get();
        Result unknown = post("/auth/login/send-code", Map.of("target", "nobody@example.com"));
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.body()).contains("\"expiresIn\":4", "\"resendIn\":60");
        // 未注册的目标不会真的收到验证码，猜测时与已注册目标的错误一致。
        assertThat(outbox.count.get()).isEqualTo(before);
        assertThat(post("/auth/code-login", Map.of("target", "nobody@example.com", "code", "123456"))
                        .body())
                .contains("\"code\":\"verification.codeInvalid\"");
    }

    private Result register(String account, String target, String code) {
        return post("/auth/register", Map.of("account", account, "password", PASSWORD, "target", target, "code", code));
    }

    private record Result(int status, String body, String cookie) {}

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }

    private Result post(String path, Object body) {
        return client().post().uri(path).body(body).exchange((request, response) -> {
            String cookie = response.getHeaders().getFirst("Set-Cookie");
            return new Result(
                    response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                    cookie == null ? null : cookie.split(";")[0]);
        });
    }

    // 与 tenant-app 相同的授权码 + PKCE 流程，返回 Access Token。
    private String tokensFor(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var authorized = client().get()
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
            String code = UriComponentsBuilder.fromUri(authorized.getHeaders().getLocation())
                    .build()
                    .getQueryParams()
                    .getFirst("code");
            Map<?, ?> tokens = client().post()
                    .uri("/auth/exchange")
                    .body(Map.of("code", code, "codeVerifier", VERIFIER))
                    .retrieve()
                    .body(Map.class);
            return (String) ((Map<?, ?>) tokens.get("data")).get("accessToken");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private Jwt decode(String accessToken) {
        return NimbusJwtDecoder.withJwkSetUri(baseUrl() + "/oauth2/jwks")
                .build()
                .decode(accessToken);
    }
}
