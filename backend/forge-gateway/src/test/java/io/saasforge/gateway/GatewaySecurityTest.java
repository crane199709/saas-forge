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
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.gateway.server.webflux.routes[0].id=demo",
                    "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/demo/**"
        })
class GatewaySecurityTest {
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

    private int status(String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri("/demo/context");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value());
    }

    static String token(RSAKey key, String issuer, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("019a0110-0000-7000-8000-000000000002")
                .audience("tenant-app")
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(expires))
                .claim("tenant_id", "019a0110-0000-7000-8000-000000000001")
                .claim("membership_id", "019a0110-0000-7000-8000-000000000003")
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
