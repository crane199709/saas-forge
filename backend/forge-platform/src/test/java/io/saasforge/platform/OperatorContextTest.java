package io.saasforge.platform;

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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 平台服务经 SDK 只接受 Platform Realm Token，并从中读取当前 Operator（ADR-0003）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorContextTest {
    static final String PLATFORM_ISSUER = "http://platform-realm.test";
    static final String OPERATOR_ID = "019a0110-0000-7000-8000-0000000000f1";
    static final RSAKey KEY;
    static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("platform-key").generate();
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

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("forge.security.platform.issuer", () -> PLATFORM_ISSUER);
        registry.add(
                "forge.security.platform.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @LocalServerPort
    int port;

    @Test
    void sdkVerifiesPlatformTokenAndExposesCurrentOperator() throws Exception {
        Map<?, ?> envelope = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/operators/current")
                .header("Authorization", "Bearer " + token(PLATFORM_ISSUER, "platform-console", null))
                .retrieve()
                .body(Map.class);
        assertThat(envelope.get("code")).isEqualTo("ok");
        assertThat(((Map<?, ?>) envelope.get("data")).get("operatorId")).isEqualTo(OPERATOR_ID);
    }

    @Test
    void sdkRejectsTenantRealmAndIncompleteTokens() throws Exception {
        assertThat(status(null)).isEqualTo(401);
        // Tenant Realm 的 Token：issuer、audience 与 claim 都属于租户侧。
        assertThat(status(token("http://localhost:8080/iam", "tenant-app", "019a0110-0000-7000-8000-000000000001")))
                .isEqualTo(401);
        assertThat(status(token(PLATFORM_ISSUER, "tenant-app", null))).isEqualTo(401);
        assertThat(status(token("http://other-realm.test", "platform-console", null)))
                .isEqualTo(401);
    }

    private int status(String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri("/operators/current");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value());
    }

    private static String token(String issuer, String audience, String tenantId) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(OPERATOR_ID)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenantId)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
