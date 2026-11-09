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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 网关按 Token 中的租户限流：一个租户超过阈值只拒绝它自己的请求，其他租户不受影响。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            "forge.gateway.rate-limit.default-qps=2",
            "forge.gateway.services[0].prefix=/orders",
            "forge.gateway.services[0].realm=tenant"
        })
class TenantRateLimitTest {
    static final String ISSUER = "http://tenant-realm.test";
    static final UUID NOISY_TENANT = UUID.fromString("019a0110-0000-7000-8000-00000000000a");
    static final UUID QUIET_TENANT = UUID.fromString("019a0110-0000-7000-8000-00000000000b");
    static final UUID MEMBERSHIP_ID = UUID.fromString("019a0110-0000-7000-8000-000000000003");

    static final SharedContainers.Redis redis = SharedContainers.redis();

    static final RSAKey KEY;
    // 同时充当 JWKS 端点与下游业务服务
    static final HttpServer DOWNSTREAM;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("tenant-key").generate();
            DOWNSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            DOWNSTREAM.setExecutor(Executors.newCachedThreadPool());
            DOWNSTREAM.createContext("/jwks", exchange -> respond(exchange, new JWKSet(KEY.toPublicJWK()).toString()));
            DOWNSTREAM.createContext("/orders/context", exchange -> respond(exchange, "{}"));
            DOWNSTREAM.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        String downstream = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "demo");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/orders/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> downstream);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.security.issuer", () -> ISSUER);
        registry.add("forge.security.jwk-set-uri", () -> downstream + "/jwks");
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void publishActiveState() {
        for (UUID tenant : List.of(NOISY_TENANT, QUIET_TENANT)) {
            redisTemplate.opsForValue().set(MembershipStateKeys.version(tenant, MEMBERSHIP_ID), "1");
            redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(tenant), "active");
            redisTemplate.opsForValue().set(MembershipStateKeys.tenantIpAllowlist(tenant), "*");
        }
    }

    @Test
    void tenantOverThresholdIsLimitedWithoutAffectingOtherTenants() throws Exception {
        String noisy = token(NOISY_TENANT);
        String quiet = token(QUIET_TENANT);
        // 先各请求一次，让 JWKS 获取等首次开销不占用限流窗口
        call(noisy);
        call(quiet);

        List<Response> burst = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            burst.add(call(noisy));
        }
        Response limited = burst.stream()
                .filter(response -> response.status() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError("noisy tenant was never limited"));
        JsonNode body = JsonMapper.builder().build().readTree(limited.body());
        assertThat(body.path("code").asString()).isEqualTo("request.rateLimited");
        assertThat(body.path("httpStatus").asInt()).isEqualTo(429);
        assertThat(body.path("userTip").asString()).isEqualTo("请求过于频繁，请稍后重试");
        assertThat(body.path("traceId").asString()).hasSize(32).isEqualTo(limited.traceIdHeader());
        assertThat(limited.retryAfter()).isEqualTo("1");

        assertThat(call(quiet).status()).isEqualTo(200);
    }

    private Response call(String token) {
        // 用 JDK 客户端：Apache HttpClient 会按 Retry-After 自动重试 429，测试就看不到限流响应
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri("/orders/context")
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> new Response(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst("X-Trace-Id"),
                        response.getHeaders().getFirst("Retry-After"),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    record Response(int status, String traceIdHeader, String retryAfter, String body) {}

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    private static String token(UUID tenantId) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("019a0110-0000-7000-8000-000000000002")
                .audience("tenant-app")
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenantId.toString())
                .claim("membership_id", MEMBERSHIP_ID.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
