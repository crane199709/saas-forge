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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 测试用 Platform Realm：本地 JWKS 端点与签发 Operator Token，供各测试类共享（进程内只启动一次）。
 * 同一端点还模拟 iam 的 {@code GET /internal/permissions}，供套餐选择权限码。
 */
final class PlatformTokens {
    static final String PLATFORM_ISSUER = "http://platform-realm.test";
    static final String OPERATOR_ID = "019a0110-0000-7000-8000-0000000000f1";
    static final String IAM_TOKEN = "test-platform-token";
    /** fixtures/plan.sql 中的默认套餐。 */
    static final String DEFAULT_PLAN_ID = "019a0200-0000-7000-8000-000000000001";

    private static final String CATALOG = """
            {"code":"ok","httpStatus":200,"data":[
              {"code":"order:read","name":"查看订单","service":"demo-service","group":"订单"},
              {"code":"order:approve","name":"审批订单","service":"demo-service","group":"订单"},
              {"code":"report:export","name":"导出报表","service":"demo-service","group":null}]}
            """;
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
            JWKS.createContext("/internal/permissions", exchange -> {
                boolean authorized = ("Bearer " + IAM_TOKEN)
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = authorized ? CATALOG.getBytes(StandardCharsets.UTF_8) : new byte[0];
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(authorized ? 200 : 401, authorized ? body.length : -1);
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
        registry.add(
                "forge.platform.iam.uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort());
        registry.add("forge.platform.iam.token", () -> IAM_TOKEN);
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
