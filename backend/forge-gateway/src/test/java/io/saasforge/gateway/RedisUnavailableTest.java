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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Redis 故障必须经整条安全过滤链表现为 503：401 会让前端刷新，而刷新同样依赖 Redis，最终把用户登出。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            // 端口 1 无服务监听，模拟 Redis 故障。
            "spring.data.redis.host=127.0.0.1",
            "spring.data.redis.port=1",
            "spring.data.redis.timeout=2s",
            "spring.cloud.gateway.server.webflux.routes[0].id=demo",
            "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/demo/**",
            "spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:1"
        })
class RedisUnavailableTest {
    static final String ISSUER = "http://tenant-realm.test";
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
            JWKS.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry registry) {
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

    @Test
    void validTokenGetsServiceUnavailableWhenStateCannotBeRead() throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("019a0110-0000-7000-8000-000000000002")
                .audience("tenant-app")
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", "019a0110-0000-7000-8000-000000000001")
                .claim("membership_id", "019a0110-0000-7000-8000-000000000003")
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("tenant-key").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        String[] result = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/demo/context")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange((req, response) -> new String[] {
                    String.valueOf(response.getStatusCode().value()),
                    response.getHeaders().getFirst("X-Trace-Id"),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)
                });
        assertThat(result[0]).isEqualTo("503");
        JsonNode body = JsonMapper.builder().build().readTree(result[2]);
        assertThat(body.path("code").asString()).isEqualTo("system.unavailable");
        assertThat(body.path("httpStatus").asInt()).isEqualTo(503);
        assertThat(body.path("errorMessage").asString()).isEqualTo("Internal error");
        assertThat(result[1]).hasSize(32);
        assertThat(body.path("traceId").asString()).isEqualTo(result[1]);
    }
}
