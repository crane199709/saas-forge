package io.saasforge.platform;

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
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.OperatorStateKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试用 Platform Realm：本地 JWKS 端点与签发 Operator Token，供各测试类共享（进程内只启动一次）。
 * 同一端点还模拟 iam 的 {@code GET /internal/permissions} 与 {@code GET /internal/permission-tree}，供套餐选择权限码。
 *
 * <p>Redis 容器同样只启动一次，模拟 iam 按 Token 版本发布的 Operator 明细：{@link #operator()} 的 Operator 为超级管理员，
 * 拥有全部平台权限码，租户数据范围为「全部」；不关心平台权限的测试因此照常访问全部接口与数据。
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
    /** 模拟的 iam Feature Pack 授权树，原样透传给前端。 */
    static final String CATALOG_TREE = """
            {"code":"ok","httpStatus":200,"data":{"menus":[
              {"code":"demo","name":"示例应用","icon":"grid","app":null,"permission":null,"actions":[],"children":[
                {"code":"demo.overview","name":"订单概览","icon":null,"app":"demo",
                 "permission":{"code":"order:read","name":"查看订单","sharedWith":[]},
                 "actions":[{"code":"order:approve","name":"审批订单","sharedWith":[]}],"children":[]}]}],
              "others":[{"code":"report:export","name":"导出报表","service":"demo-service","group":null}]}}
            """;
    /** 清单中声明的全部平台权限码，即超级管理员的权限。 */
    static final List<String> ALL_PERMISSIONS = List.of(
            "platform:tenant:read",
            "platform:tenant:manage",
            "platform:plan:manage",
            "platform:sub-app:manage",
            "platform:ops:read",
            "platform:ops:manage");

    /** 模拟的 iam 权限码目录是否可用；改为 false 的测试须在结束时恢复。 */
    static volatile boolean catalogAvailable = true;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final StringRedisTemplate GRANTS;

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
            JWKS.createContext("/internal/permissions", exchange -> catalog(exchange, CATALOG));
            JWKS.createContext("/internal/permission-tree", exchange -> catalog(exchange, CATALOG_TREE));
            JWKS.createContext("/internal/services", exchange -> {
                boolean authorized = ("Bearer " + IAM_TOKEN)
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body =
                        "{\"code\":\"ok\",\"httpStatus\":200,\"data\":[\"demo-service\",\"offline-service\",\"unsupported-service\",\"unreachable-service\",\"unconfigured-service\"]}"
                                .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(authorized ? 200 : 401, authorized ? body.length : -1);
                if (authorized) exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.createContext("/internal/tenants/", IamPlanImpactFixture::handle);
            JWKS.createContext("/internal/ops", exchange -> {
                String token = exchange.getRequestHeaders().getFirst("Authorization");
                int status = "Bearer demo-ops-token".equals(token)
                        ? 200
                        : "Bearer unsupported-ops-token".equals(token) ? 404 : 401;
                byte[] body = "{\"code\":\"ok\",\"httpStatus\":200,\"data\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.createContext("/internal/ops/command-rejected", exchange -> {
                byte[] body =
                        "{\"code\":\"deadEvent.alreadyHandled\",\"httpStatus\":409,\"errorMessage\":\"Already handled\"}"
                                .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(409, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.createContext("/internal/ops/page", exchange -> {
                boolean valid = "GET".equals(exchange.getRequestMethod())
                        && "size=2".equals(exchange.getRequestURI().getRawQuery())
                        && "Bearer demo-ops-token"
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body =
                        "{\"code\":\"ok\",\"httpStatus\":200,\"data\":[\"event-a\",\"event-b\"],\"meta\":{\"page\":{\"number\":1,\"size\":2,\"totalElements\":3,\"totalPages\":2}}}"
                                .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.createContext("/internal/ops/command", exchange -> {
                boolean valid = "POST".equals(exchange.getRequestMethod())
                        && "Bearer demo-ops-token"
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        && "重放已修复的事件"
                                .equals(JSON.readTree(exchange.getRequestBody())
                                        .path("reason")
                                        .asString());
                byte[] body = "{\"code\":\"ok\",\"httpStatus\":200,\"data\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.createContext("/internal/ops/dead-events", DeadEventOpsFixture::handle);
            JWKS.createContext("/internal/ops/tasks", TaskOpsFixture::handle);
            JWKS.start();
            REDIS.start();
            var factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
            factory.afterPropertiesSet();
            GRANTS = new StringRedisTemplate(factory);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private PlatformTokens() {}

    // 模拟 iam 的权限码目录接口：校验上报令牌，catalogAvailable 为 false 时返回 503。
    private static void catalog(HttpExchange exchange, String catalog) throws IOException {
        boolean authorized =
                ("Bearer " + IAM_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
        int status = !authorized ? 401 : catalogAvailable ? 200 : 503;
        byte[] body = status == 200 ? catalog.getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
        try (var stream = exchange.getResponseBody()) {
            stream.write(body);
        }
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("forge.security.platform.issuer", () -> PLATFORM_ISSUER);
        registry.add(
                "forge.security.platform.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        registry.add(
                "forge.platform.iam.uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort());
        registry.add("forge.platform.iam.token", () -> IAM_TOKEN);
        registry.add("forge.platform.ops.tokens.demo-service", () -> "demo-ops-token");
        registry.add("forge.platform.ops.tokens.unsupported-service", () -> "unsupported-ops-token");
        registry.add("forge.platform.ops.tokens.offline-service", () -> "offline-ops-token");
        registry.add(
                "spring.cloud.discovery.client.simple.instances.demo-service[0].uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort());
        registry.add(
                "spring.cloud.discovery.client.simple.instances.unsupported-service[0].uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort());
        registry.add("forge.platform.ops.tokens.unreachable-service", () -> "unreachable-ops-token");
        registry.add(
                "spring.cloud.discovery.client.simple.instances.unreachable-service[0].uri",
                () -> "http://127.0.0.1:1");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** 超级管理员 Operator 的 Token，版本 1。 */
    static String operator() throws Exception {
        publish(UUID.fromString(OPERATOR_ID), 1, ALL_PERMISSIONS, null, List.of(DataScope.ALL.template()));
        return token(PLATFORM_ISSUER, "platform-console", null);
    }

    /**
     * 以给定的平台权限码、负责区域与租户 Data Policy 发布 Operator 的明细，并签发其版本 1 的 Token。
     *
     * @param tenantPolicies 每条相当于一个持有该策略的平台角色，为空表示租户上没有任何策略
     */
    static String operator(UUID operatorId, Collection<String> permissions, String region, List<String> tenantPolicies)
            throws Exception {
        publish(operatorId, 1, permissions, region, tenantPolicies);
        return token(PLATFORM_ISSUER, "platform-console", null, operatorId.toString(), 1);
    }

    private static void publish(
            UUID operatorId, long version, Collection<String> permissions, String region, List<String> tenantPolicies) {
        var subject = new SubjectAttributes(
                Set.of(),
                null,
                Set.of(),
                region == null ? Map.of() : Map.of("region", region),
                Map.of("region", "string"));
        var grants = new MembershipGrants(
                Set.of(),
                Set.copyOf(permissions),
                subject,
                tenantPolicies.isEmpty() ? Map.of() : Map.of("tenant", tenantPolicies));
        GRANTS.opsForValue().set(OperatorStateKeys.grants(operatorId, version), JSON.writeValueAsString(grants));
    }

    static String token(String issuer, String audience, String tenantId) throws Exception {
        return token(issuer, audience, tenantId, OPERATOR_ID, 1);
    }

    static String token(String issuer, String audience, String tenantId, String subject, Integer version)
            throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenantId)
                .claim("ver", version)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
