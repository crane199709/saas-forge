package io.saasforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.gateway.server.webflux.routes[0].id=demo",
                    "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/demo/**"
        })
@Testcontainers
class GatewaySecurityTest {
    static final String ISSUER = "http://tenant-realm.test";
    static final UUID TENANT_ID = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID MEMBERSHIP_ID = UUID.fromString("019a0110-0000-7000-8000-000000000003");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final RSAKey KEY;
    static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("tenant-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            JWKS.createContext("/demo/context", exchange -> {
                byte[] body = "{}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            JWKS.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "demo");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/demo/**");
        registry.add(
                "spring.cloud.gateway.server.webflux.routes[0].uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("forge.security.issuer", () -> ISSUER);
        registry.add(
                "forge.security.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    // 模拟 iam 签发 Token 时发布的状态：Membership 版本 1、租户 active。
    @BeforeEach
    void publishActiveState() {
        redisTemplate.opsForValue().set(MembershipStateKeys.version(TENANT_ID, MEMBERSHIP_ID), "1");
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(TENANT_ID), "active");
    }

    @Test
    void incrementedMembershipVersionRejectsOldTokenAndAcceptsRefreshedOne() throws Exception {
        String old = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        assertThat(status(old)).isEqualTo(200);
        // 停用 Membership 或修改角色时 iam 递增版本：下一次请求即被拒绝，无需等待 Token 过期。
        redisTemplate.opsForValue().set(MembershipStateKeys.version(TENANT_ID, MEMBERSHIP_ID), "2");
        assertThat(status(old)).isEqualTo(401);
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900), 2)))
                .isEqualTo(200);
    }

    @Test
    void suspendedTenantRejectsOtherwiseValidToken() throws Exception {
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(TENANT_ID), "suspended");
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900)))).isEqualTo(401);
    }

    @Test
    void missingPublishedStateRejectsUntilRefreshRepublishesIt() throws Exception {
        redisTemplate.delete(MembershipStateKeys.version(TENANT_ID, MEMBERSHIP_ID));
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900)))).isEqualTo(401);
        publishActiveState();
        redisTemplate.delete(MembershipStateKeys.tenantStatus(TENANT_ID));
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900)))).isEqualTo(401);
    }

    @Test
    void rejectsMissingForgedWrongRealmAndExpiredTokens() throws Exception {
        assertThat(status(null)).isEqualTo(401);
        assertThat(status(token(
                        new RSAKeyGenerator(2048).generate(),
                        ISSUER,
                        Instant.now().plusSeconds(900))))
                .isEqualTo(401);
        assertThat(status(token(KEY, "http://platform-realm.test", Instant.now().plusSeconds(900))))
                .isEqualTo(401);
        assertThat(status(token(KEY, ISSUER, Instant.now().minusSeconds(120)))).isEqualTo(401);
    }

    @Test
    void forwardsValidTenantToken() throws Exception {
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900)))).isEqualTo(200);
    }

    @Test
    void tenantTokenCannotAccessPlatformAndNeedsCompleteContext() throws Exception {
        String token = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        int platformStatus = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/platform/anything")
                .header("Authorization", "Bearer " + token)
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(platformStatus).isEqualTo(403);
        var original = SignedJWT.parse(token);
        var incomplete = new SignedJWT(
                original.getHeader(),
                new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                        .claim("tenant_id", null)
                        .build());
        incomplete.sign(new RSASSASigner(KEY));
        assertThat(status(incomplete.serialize())).isEqualTo(401);
    }

    @Test
    void internalTokenEndpointIsNotExposedByGateway() throws Exception {
        var client = RestClient.create("http://localhost:" + port);
        int anonymous = client.post()
                .uri("/iam/oauth2/token")
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(anonymous).isEqualTo(401);
        int authenticated = client.post()
                .uri("/iam/oauth2/token")
                .header(
                        "Authorization",
                        "Bearer " + token(KEY, ISSUER, Instant.now().plusSeconds(900)))
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(authenticated).isEqualTo(403);
    }

    @Test
    void tenantSwitchEndpointsAreReachableWithTheirOwnCredentials() throws Exception {
        var client = RestClient.create("http://localhost:" + port);
        // 测试未配置 IAM 路由：通过安全规则后得到 404，被拦截则为 401/403。
        int switchStatus = client.post()
                .uri("/iam/auth/switch")
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(switchStatus).isEqualTo(404);
        int anonymousListing = client.get()
                .uri("/iam/auth/memberships")
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(anonymousListing).isEqualTo(401);
        int listing = client.get()
                .uri("/iam/auth/memberships")
                .header(
                        "Authorization",
                        "Bearer " + token(KEY, ISSUER, Instant.now().plusSeconds(900)))
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(listing).isEqualTo(404);
    }

    private int status(String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri("/demo/context");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value());
    }

    static String token(RSAKey key, String issuer, Instant expires) throws Exception {
        return token(key, issuer, expires, 1);
    }

    static String token(RSAKey key, String issuer, Instant expires, long version) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("019a0110-0000-7000-8000-000000000002")
                .audience("tenant-app")
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(expires))
                .claim("tenant_id", TENANT_ID.toString())
                .claim("membership_id", MEMBERSHIP_ID.toString())
                .claim("ver", version)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
