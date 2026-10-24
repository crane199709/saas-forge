package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantLoginTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Test
    void emailLoginAndJwksVerifyRequiredClaims() {
        Map<?, ?> response = login("demo@example.com", "Demo-password-123!");
        var decoder = NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/oauth2/jwks")
                .build();
        var jwt = decoder.decode((String) response.get("accessToken"));
        assertThat(jwt.getSubject()).isEqualTo("019a0110-0000-7000-8000-000000000002");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("019a0110-0000-7000-8000-000000000001");
        assertThat(jwt.getClaimAsString("membership_id")).isEqualTo("019a0110-0000-7000-8000-000000000003");
        assertThat(((Number) jwt.getClaim("ver")).longValue()).isEqualTo(1);
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:8080/iam");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        Map<?, ?> jwks = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/oauth2/jwks")
                .retrieve()
                .body(Map.class);
        for (Object key : (List<?>) jwks.get("keys")) {
            assertThat(((Map<?, ?>) key).containsKey("d")).isFalse();
            assertThat(((Map<?, ?>) key).containsKey("p")).isFalse();
            assertThat(((Map<?, ?>) key).containsKey("q")).isFalse();
        }
    }

    @Test
    void wrongPasswordAndUnknownIdentityHaveIdenticalFailure() {
        String known = failure("demo", "incorrect");
        assertThat(failure("unknown", "incorrect")).isEqualTo(known).contains("auth.failed");
    }

    @Test
    void refreshRotatesAndRejectsReusedOrUnknownCredentials() {
        Map<?, ?> original = login("demo", "Demo-password-123!");
        String refresh = (String) original.get("refreshToken");
        Map<?, ?> rotated = refresh(refresh);
        assertThat(rotated.get("accessToken")).isNotEqualTo(original.get("accessToken"));
        assertThat(rotated.get("refreshToken")).isNotEqualTo(refresh);
        assertThatThrownBy(() -> refresh(refresh)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThatThrownBy(() -> refresh("invalid")).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThat(refresh((String) rotated.get("refreshToken")).get("accessToken"))
                .isInstanceOf(String.class);
    }

    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    private Map<?, ?> login(String account, String password) {
        String code = authorize(account, password);
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/auth/exchange")
                .body(Map.of("code", code, "codeVerifier", VERIFIER))
                .retrieve()
                .body(Map.class);
    }

    private String authorize(String account, String password) {
        var client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
        var login = client.post()
                .uri("/auth/login")
                .body(Map.of("account", account, "password", password))
                .retrieve()
                .toEntity(Map.class);
        String cookie = login.getHeaders().getFirst("Set-Cookie").split(";")[0];
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var response = client.get()
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
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            var query = UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                    .build()
                    .getQueryParams();
            assertThat(query.getFirst("state")).isEqualTo("test-state");
            assertThat(query.getFirst("error")).isNull();
            return query.getFirst("code");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void standardTokenEndpointEnforcesPkceAndRotatesRefreshToken() {
        String code = authorize("demo", "Demo-password-123!");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", "http://localhost:5174/");
        form.add("code_verifier", VERIFIER);
        Map<?, ?> response = tokenGrant(form);
        assertThat(response.get("access_token")).isInstanceOf(String.class);
        assertThatThrownBy(() -> tokenGrant(form)).isInstanceOf(HttpClientErrorException.BadRequest.class);
        String otherCode = authorize("demo", "Demo-password-123!");
        form.set("code", otherCode);
        form.set("code_verifier", "wrong-verifier-abcdefghijklmnopqrstuvwxyz0123456789");
        assertThatThrownBy(() -> tokenGrant(form)).isInstanceOf(HttpClientErrorException.BadRequest.class);
        Map<?, ?> fresh = login("demo", "Demo-password-123!");
        var refresh = new LinkedMultiValueMap<String, String>();
        refresh.add("grant_type", "refresh_token");
        refresh.add("refresh_token", (String) fresh.get("refreshToken"));
        assertThat(tokenGrant(refresh).get("access_token")).isInstanceOf(String.class);
        assertThatThrownBy(() -> tokenGrant(refresh)).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void concurrentRefreshOnlyConsumesCredentialOnce() throws Exception {
        String token = (String) login("demo", "Demo-password-123!").get("refreshToken");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            ready.await();
            return RestClient.create("http://localhost:" + port)
                    .post()
                    .uri("/auth/refresh")
                    .body(Map.of("refreshToken", token))
                    .exchange((req, response) -> response.getStatusCode().value());
        };
        try {
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 401);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void consumedAuthorizationCodeAndAccessTokenCannotBeUsedAsRefreshCredentials() {
        String code = authorize("demo", "Demo-password-123!");
        Map<?, ?> issued = RestClient.create("http://localhost:" + port)
                .post()
                .uri("/auth/exchange")
                .body(Map.of("code", code, "codeVerifier", VERIFIER))
                .retrieve()
                .body(Map.class);
        assertThatThrownBy(() -> refresh(code)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", (String) issued.get("accessToken"));
        assertThatThrownBy(() -> tokenGrant(form)).isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThat(refresh((String) issued.get("refreshToken")).get("accessToken"))
                .isInstanceOf(String.class);
    }

    @Test
    void irrelevantRefreshParameterCannotBypassAuthorizationCodeConsumption() throws Exception {
        String code = authorize("demo", "Demo-password-123!");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        Function<String, Callable<Integer>> request = noise -> () -> {
            ready.await();
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", "http://localhost:5174/");
            form.add("code_verifier", VERIFIER);
            form.add("refresh_token", noise);
            return RestClient.create("http://localhost:" + port)
                    .post()
                    .uri("/oauth2/token")
                    .headers(headers -> headers.setBasicAuth("tenant-app", "test-client-secret"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, response) -> response.getStatusCode().value());
        };
        try {
            var first = executor.submit(request.apply("unrelated-one"));
            var second = executor.submit(request.apply("unrelated-two"));
            ready.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<?, ?> tokenGrant(LinkedMultiValueMap<String, String> form) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/oauth2/token")
                .headers(headers -> headers.setBasicAuth("tenant-app", "test-client-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    private Map<?, ?> refresh(String token) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/auth/refresh")
                .body(Map.of("refreshToken", token))
                .retrieve()
                .body(Map.class);
    }

    private String failure(String account, String password) {
        try {
            login(account, password);
            throw new AssertionError("Login must fail");
        } catch (HttpClientErrorException.Unauthorized error) {
            return error.getResponseBodyAsString();
        }
    }

    @Test
    void accountAndPasswordIssueTokens() {
        Map<?, ?> response = login("demo", "Demo-password-123!");
        assertThat(response.get("accessToken")).isInstanceOf(String.class);
        assertThat(response.get("refreshToken")).isInstanceOf(String.class);
        assertThat(((Number) response.get("expiresIn")).intValue()).isBetween(899, 900);
    }
}
