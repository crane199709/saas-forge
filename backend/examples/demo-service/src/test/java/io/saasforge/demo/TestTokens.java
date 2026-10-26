package io.saasforge.demo;

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
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 模拟 Tenant Realm：本地 JWKS 端点与签发 Token，测试进程内共享，避免每个测试类重新生成密钥。 */
final class TestTokens {
    static final String ISSUER = "http://tenant-realm.test";
    static final UUID IDENTITY_ID = UUID.fromString("019a0110-0000-7000-8000-000000000002");
    static final UUID TENANT_ID = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID MEMBERSHIP_ID = UUID.fromString("019a0110-0000-7000-8000-000000000003");
    static final RSAKey KEY;
    private static final HttpServer JWKS;

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

    private TestTokens() {}

    static void register(DynamicPropertyRegistry registry) {
        registry.add("forge.security.issuer", () -> ISSUER);
        registry.add(
                "forge.security.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    static String forTenant(UUID tenantId) throws Exception {
        return token(KEY, ISSUER, Instant.now().plusSeconds(900), tenantId);
    }

    /** 同一 Identity 在另一 Membership 下的 Token，对应切换租户后 IAM 换发的结果。 */
    static String forMembership(UUID tenantId, UUID membershipId) throws Exception {
        return token(KEY, ISSUER, Instant.now().plusSeconds(900), tenantId, membershipId);
    }

    static String token(RSAKey key, String issuer, Instant expires, UUID tenantId) throws Exception {
        return token(key, issuer, expires, tenantId, MEMBERSHIP_ID);
    }

    private static String token(RSAKey key, String issuer, Instant expires, UUID tenantId, UUID membershipId)
            throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(IDENTITY_ID.toString())
                .audience("tenant-app")
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(expires))
                .claim("tenant_id", tenantId.toString())
                .claim("membership_id", membershipId.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
