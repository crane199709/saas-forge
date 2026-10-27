package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Platform Realm：Operator 登录、独立 issuer 与密钥，以及两个 Realm 共用授权端点时不能交叉签发（ADR-0003）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformLoginTest {
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

    private static final String PLATFORM_ISSUER = "http://localhost:8080/iam/platform";
    private static final String OPERATOR_ID = "019a0110-0000-7000-8000-0000000000f1";
    private static final String OPERATOR_PASSWORD = "Operator-password-123!";
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @LocalServerPort
    int port;

    @Test
    void operatorObtainsPlatformTokenThatOnlyPlatformJwksVerifies() {
        Map<?, ?> tokens = exchange("/platform/auth/exchange", authorize(platformSession(), Realm.PLATFORM));
        assertThat(tokens.get("operatorName")).isEqualTo("演示运营");
        assertThat(((Number) tokens.get("expiresIn")).intValue()).isBetween(899, 900);
        String access = (String) tokens.get("accessToken");

        Jwt jwt = decoder("/platform/oauth2/jwks").decode(access);
        assertThat(jwt.getIssuer().toString()).isEqualTo(PLATFORM_ISSUER);
        assertThat(jwt.getSubject()).isEqualTo(OPERATOR_ID);
        assertThat(jwt.getAudience()).containsExactly("platform-console");
        assertThat(jwt.hasClaim("tenant_id")).isFalse();
        assertThat(jwt.hasClaim("membership_id")).isFalse();
        // 独立签名密钥：Tenant Realm 的 JWKS 中没有能验证该 Token 的公钥。
        assertThatThrownBy(() -> decoder("/oauth2/jwks").decode(access)).isInstanceOf(JwtException.class);

        Map<?, ?> refreshed = data(RestClient.create(baseUrl())
                .post()
                .uri("/platform/auth/refresh")
                .body(Map.of("refreshToken", tokens.get("refreshToken")))
                .retrieve()
                .body(Map.class));
        assertThat(decoder("/platform/oauth2/jwks")
                        .decode((String) refreshed.get("accessToken"))
                        .getIssuer()
                        .toString())
                .isEqualTo(PLATFORM_ISSUER);
        assertThat(refreshed.get("refreshToken")).isNotEqualTo(tokens.get("refreshToken"));
    }

    @Test
    void accountsOfOneRealmCannotSignInToTheOther() {
        assertThat(loginStatus("/platform/auth/login", "demo", "Demo-password-123!"))
                .isEqualTo(401);
        assertThat(loginStatus("/auth/login", "operator", OPERATOR_PASSWORD)).isEqualTo(401);
    }

    @Test
    void sharedAuthorizationEndpointDoesNotIssueTokensAcrossRealms() {
        // 同一浏览器的 IAM 登录会话可以向任一客户端申请授权码，签发时按会话主体的 Realm 拒绝。
        String identitySession = session("/auth/login", "demo", "Demo-password-123!");
        String codeForPlatform = authorize(identitySession, Realm.PLATFORM);
        assertThatThrownBy(() -> exchange("/platform/auth/exchange", codeForPlatform))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        String codeForTenant = authorize(platformSession(), Realm.TENANT);
        assertThatThrownBy(() -> exchange("/auth/exchange", codeForTenant))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachRealmPublishesItsOwnDiscoveryMetadataAndKeys() {
        Map<?, ?> tenant = get("/.well-known/openid-configuration");
        Map<?, ?> platform = get("/platform/.well-known/openid-configuration");
        assertThat(tenant.get("issuer")).isEqualTo("http://localhost:8080/iam");
        assertThat(tenant.get("jwks_uri")).isEqualTo("http://localhost:8080/iam/oauth2/jwks");
        assertThat(platform.get("issuer")).isEqualTo(PLATFORM_ISSUER);
        assertThat(platform.get("jwks_uri")).isEqualTo(PLATFORM_ISSUER + "/oauth2/jwks");
        assertThat((List<Object>) platform.get("scopes_supported")).containsExactly("platform");
        assertThat((List<Object>) platform.get("code_challenge_methods_supported"))
                .containsExactly("S256");

        List<?> platformKeys = (List<?>) get("/platform/oauth2/jwks").get("keys");
        assertThat(platformKeys).hasSize(1);
        assertThat((Map<Object, Object>) platformKeys.get(0)).doesNotContainKeys("d", "p", "q");
        Object platformKid = ((Map<?, ?>) platformKeys.get(0)).get("kid");
        assertThat((List<?>) get("/oauth2/jwks").get("keys"))
                .<Object>extracting(key -> ((Map<?, ?>) key).get("kid"))
                .doesNotContain(platformKid);
    }

    private enum Realm {
        TENANT("tenant-app", "http://localhost:5174/", "tenant"),
        PLATFORM("platform-console", "http://localhost:5173/", "platform");

        final String clientId;
        final String redirectUri;
        final String scope;

        Realm(String clientId, String redirectUri, String scope) {
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.scope = scope;
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private NimbusJwtDecoder decoder(String jwksPath) {
        return NimbusJwtDecoder.withJwkSetUri(baseUrl() + jwksPath).build();
    }

    private Map<?, ?> get(String path) {
        return RestClient.create(baseUrl()).get().uri(path).retrieve().body(Map.class);
    }

    private RestClient noRedirectClient() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }

    private String platformSession() {
        return session("/platform/auth/login", "operator", OPERATOR_PASSWORD);
    }

    private String session(String path, String account, String password) {
        var login = noRedirectClient()
                .post()
                .uri(path)
                .body(Map.of("account", account, "password", password))
                .retrieve()
                .toEntity(Map.class);
        assertThat(login.getHeaders().getCacheControl()).isEqualTo("no-store");
        data(login.getBody());
        return login.getHeaders().getFirst("Set-Cookie").split(";")[0];
    }

    private int loginStatus(String path, String account, String password) {
        return noRedirectClient()
                .post()
                .uri(path)
                .body(Map.of("account", account, "password", password))
                .exchange((req, response) -> response.getStatusCode().value());
    }

    private String authorize(String cookie, Realm realm) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var response = noRedirectClient()
                    .get()
                    .uri(builder -> builder.path("/oauth2/authorize")
                            .queryParam("response_type", "code")
                            .queryParam("client_id", realm.clientId)
                            .queryParam("redirect_uri", realm.redirectUri)
                            .queryParam("scope", realm.scope)
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
            assertThat(query.getFirst("error")).isNull();
            return query.getFirst("code");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Map<?, ?> exchange(String path, String code) {
        return data(RestClient.create(baseUrl())
                .post()
                .uri(path)
                .body(Map.of("code", code, "codeVerifier", VERIFIER))
                .retrieve()
                .body(Map.class));
    }

    private static <T> T data(Map<?, ?> envelope) {
        assertThat(envelope.get("code")).isEqualTo("ok");
        @SuppressWarnings("unchecked")
        T data = (T) envelope.get("data");
        return data;
    }
}
