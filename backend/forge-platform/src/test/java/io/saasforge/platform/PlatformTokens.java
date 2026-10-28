package io.saasforge.platform;

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
import org.springframework.test.context.DynamicPropertyRegistry;

/** 测试用 Platform Realm：本地 JWKS 端点与签发 Operator Token，供各测试类共享（进程内只启动一次）。 */
final class PlatformTokens {
    static final String PLATFORM_ISSUER = "http://platform-realm.test";
    static final String OPERATOR_ID = "019a0110-0000-7000-8000-0000000000f1";
    private static final RSAKey KEY;
    private static final HttpServer JWKS;

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

    private PlatformTokens() {}

    static void register(DynamicPropertyRegistry registry) {
        registry.add("forge.security.platform.issuer", () -> PLATFORM_ISSUER);
        registry.add(
                "forge.security.platform.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    static String operator() throws Exception {
        return token(PLATFORM_ISSUER, "platform-console", null);
    }

    static String token(String issuer, String audience, String tenantId) throws Exception {
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
