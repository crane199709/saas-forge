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
import io.saasforge.gateway.routing.config.GatewayServicesProperties;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.OperatorStateKeys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
            "forge.gateway.services[0].prefix=/iam", "forge.gateway.services[0].realm=tenant",
            "forge.gateway.services[1].prefix=/platform", "forge.gateway.services[1].realm=platform",
            "forge.gateway.services[2].prefix=/orders", "forge.gateway.services[2].realm=tenant",
            "forge.gateway.services[3].prefix=/control", "forge.gateway.services[3].realm=platform",
            "spring.cloud.gateway.server.webflux.routes[0].id=demo",
                    "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/orders/**"
        })
class GatewaySecurityTest {
    static final String ISSUER = "http://tenant-realm.test";
    static final String PLATFORM_ISSUER = "http://platform-realm.test";
    static final UUID TENANT_ID = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID MEMBERSHIP_ID = UUID.fromString("019a0110-0000-7000-8000-000000000003");
    static final UUID OPERATOR_ID = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");
    static final String DOWNSTREAM_ENVELOPE = "{\"code\":\"order.notFound\",\"httpStatus\":404,"
            + "\"errorMessage\":\"Order o-1 not found\",\"userTip\":\"订单不存在\",\"traceId\":\"from-service\"}";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<String> RECEIVED_TRACEPARENT = new AtomicReference<>();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    static final RSAKey KEY;
    static final RSAKey PLATFORM_KEY;
    // 同时充当 JWKS 端点与下游业务服务
    static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("tenant-key").generate();
            PLATFORM_KEY = new RSAKeyGenerator(2048).keyID("platform-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.setExecutor(Executors.newCachedThreadPool());
            JWKS.createContext("/jwks", exchange -> respond(exchange, 200, new JWKSet(KEY.toPublicJWK()).toString()));
            JWKS.createContext(
                    "/platform-jwks",
                    exchange -> respond(exchange, 200, new JWKSet(PLATFORM_KEY.toPublicJWK()).toString()));
            JWKS.createContext("/platform/", exchange -> respond(exchange, 200, "{}"));
            JWKS.createContext("/orders/context", exchange -> {
                RECEIVED_TRACEPARENT.set(exchange.getRequestHeaders().getFirst("traceparent"));
                respond(exchange, 200, "{}");
            });
            JWKS.createContext("/orders/v3/api-docs", exchange -> respond(exchange, 200, "{}"));
            JWKS.createContext("/orders/missing", exchange -> respond(exchange, 404, DOWNSTREAM_ENVELOPE));
            JWKS.createContext("/orders/slow", exchange -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{}");
            });
            JWKS.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry registry) {
        String downstream = "http://127.0.0.1:" + JWKS.getAddress().getPort();
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "demo");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/orders/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> downstream);
        // 端口 1 无服务监听，模拟下游不可用
        registry.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "unavailable");
        registry.add(
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]", () -> "Path=/orders/unavailable/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri", () -> "http://127.0.0.1:1");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].order", () -> "-1");
        registry.add("spring.cloud.gateway.server.webflux.routes[2].id", () -> "slow");
        registry.add("spring.cloud.gateway.server.webflux.routes[2].predicates[0]", () -> "Path=/orders/slow");
        registry.add("spring.cloud.gateway.server.webflux.routes[2].uri", () -> downstream);
        registry.add("spring.cloud.gateway.server.webflux.routes[2].order", () -> "-1");
        registry.add("spring.cloud.gateway.server.webflux.routes[2].metadata.response-timeout", () -> "300");
        registry.add("spring.cloud.gateway.server.webflux.routes[3].id", () -> "platform");
        registry.add("spring.cloud.gateway.server.webflux.routes[3].predicates[0]", () -> "Path=/platform/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[3].uri", () -> downstream);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.security.issuer", () -> ISSUER);
        registry.add("forge.security.jwk-set-uri", () -> downstream + "/jwks");
        registry.add("forge.security.platform.issuer", () -> PLATFORM_ISSUER);
        registry.add("forge.security.platform.jwk-set-uri", () -> downstream + "/platform-jwks");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    /** 网关自身处理的请求抛出未知异常，用于验证 500 不泄漏异常信息。 */
    @TestConfiguration
    static class FailingEndpoint {
        @Bean
        RouterFunction<ServerResponse> failingRoute() {
            return RouterFunctions.route()
                    .GET("/orders/boom", request -> {
                        throw new IllegalStateException("redis password=secret leaked");
                    })
                    .build();
        }
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
        redisTemplate.opsForValue().set(OperatorStateKeys.version(OPERATOR_ID), "1");
    }

    @Test
    void incrementedMembershipVersionRejectsOldTokenAndAcceptsRefreshedOne() throws Exception {
        String old = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        assertThat(status(old)).isEqualTo(200);
        // 停用 Membership 或修改角色时 iam 递增版本：下一次请求即被拒绝，无需等待 Token 过期。
        redisTemplate.opsForValue().set(MembershipStateKeys.version(TENANT_ID, MEMBERSHIP_ID), "2");
        assertUnauthenticated(old);
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900), 2)))
                .isEqualTo(200);
    }

    @Test
    void suspendedTenantRejectsOtherwiseValidToken() throws Exception {
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(TENANT_ID), "suspended");
        assertUnauthenticated(token(KEY, ISSUER, Instant.now().plusSeconds(900)));
    }

    // Tenant Lifecycle（工单 15）：试用与正常可访问，其余状态的成员请求在下一次请求即被拒绝。
    @Test
    void onlyTrialAndActiveTenantsAreAccessible() throws Exception {
        String token = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(TENANT_ID), "trial");
        assertThat(status(token)).isEqualTo(200);
        for (String status : List.of("pending", "expired", "archived", "deleted")) {
            redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(TENANT_ID), status);
            assertUnauthenticated(token);
        }
    }

    @Test
    void missingPublishedStateRejectsUntilRefreshRepublishesIt() throws Exception {
        redisTemplate.delete(MembershipStateKeys.version(TENANT_ID, MEMBERSHIP_ID));
        assertUnauthenticated(token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        publishActiveState();
        redisTemplate.delete(MembershipStateKeys.tenantStatus(TENANT_ID));
        assertUnauthenticated(token(KEY, ISSUER, Instant.now().plusSeconds(900)));
    }

    @Test
    void rejectsMissingForgedWrongRealmAndExpiredTokens() throws Exception {
        assertUnauthenticated(null);
        assertUnauthenticated(token(
                new RSAKeyGenerator(2048).generate(), ISSUER, Instant.now().plusSeconds(900)));
        assertUnauthenticated(
                token(KEY, "http://platform-realm.test", Instant.now().plusSeconds(900)));
        assertUnauthenticated(token(KEY, ISSUER, Instant.now().minusSeconds(120)));
    }

    @Test
    void forwardsValidTenantToken() throws Exception {
        assertThat(status(token(KEY, ISSUER, Instant.now().plusSeconds(900)))).isEqualTo(200);
    }

    // 下游从 traceparent 继承同一 trace，日志中的 traceId 因此与响应头一致
    @Test
    void propagatesTraceToDownstreamAndReturnsTraceIdHeader() throws Exception {
        RECEIVED_TRACEPARENT.set(null);
        Response response = call(
                HttpMethod.GET,
                "/orders/context",
                token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.traceIdHeader()).hasSize(32);
        assertThat(RECEIVED_TRACEPARENT.get()).isNotNull();
        assertThat(RECEIVED_TRACEPARENT.get().split("-")[1]).isEqualTo(response.traceIdHeader());
    }

    @Test
    void passesDownstreamEnvelopeThroughUnchanged() throws Exception {
        Response response = call(
                HttpMethod.GET,
                "/orders/missing",
                token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.rawBody()).isEqualTo(DOWNSTREAM_ENVELOPE);
        assertThat(response.traceIdHeader()).hasSize(32);
    }

    @Test
    void unavailableDownstreamIs503Envelope() throws Exception {
        Response response = call(
                HttpMethod.GET,
                "/orders/unavailable/x",
                token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertEnvelope(response, 503, "system.unavailable");
        assertThat(response.body().path("errorMessage").asString()).isEqualTo("Internal error");
        assertThat(response.body().path("userTip").asString()).isEqualTo("服务暂时不可用，请稍后重试");
    }

    @Test
    void downstreamTimeoutIs503Envelope() throws Exception {
        Response response = call(
                HttpMethod.GET, "/orders/slow", token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertEnvelope(response, 503, "system.unavailable");
        assertThat(response.body().path("userTip").asString()).isEqualTo("服务暂时不可用，请稍后重试");
    }

    @Test
    void unknownGatewayErrorIs500EnvelopeWithoutExceptionText() throws Exception {
        Response response = call(
                HttpMethod.GET, "/orders/boom", token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertEnvelope(response, 500, "system.error");
        assertThat(response.body().path("errorMessage").asString()).isEqualTo("Internal error");
        assertThat(response.body().path("userTip").asString()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(response.rawBody()).doesNotContain("secret").doesNotContain("IllegalStateException");
    }

    @Test
    void tenantRealmTokenIsRejectedOnPlatformRoutes() throws Exception {
        assertUnauthenticatedAt(
                "/platform/operators/current", token(KEY, ISSUER, Instant.now().plusSeconds(900)));
    }

    @Test
    void platformRealmTokenReachesPlatformRoutesOnly() throws Exception {
        String operator = platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console");
        assertThat(call(HttpMethod.GET, "/platform/operators/current", operator).status())
                .isEqualTo(200);
        assertUnauthenticatedAt("/orders/context", operator);
        assertUnauthenticatedAt("/iam/auth/memberships", operator);
        // issuer 正确但用 Tenant Realm 密钥签名、或不是签发给 platform-console 的 Token 同样被拒。
        assertUnauthenticatedAt("/platform/operators/current", platformToken(KEY, PLATFORM_ISSUER, "platform-console"));
        assertUnauthenticatedAt(
                "/platform/operators/current", platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "tenant-app"));
        assertUnauthenticatedAt("/platform/operators/current", null);
    }

    @Test
    void changedOrMissingOperatorVersionRejectsOldPlatformToken() throws Exception {
        String operator = platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console");
        // 停用或角色变更后 iam 递增版本，旧 Token 立即失效；刷新后按新版本签发
        redisTemplate.opsForValue().set(OperatorStateKeys.version(OPERATOR_ID), "2");
        assertUnauthenticatedAt("/platform/operators/current", operator);
        assertThat(call(
                                HttpMethod.GET,
                                "/platform/operators/current",
                                platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console", 2))
                        .status())
                .isEqualTo(200);
        redisTemplate.delete(OperatorStateKeys.version(OPERATOR_ID));
        assertUnauthenticatedAt("/platform/operators/current", operator);
        // 没有 ver 的 Token（工单 22 之前签发）同样被拒，前端刷新后换发
        assertUnauthenticatedAt(
                "/platform/operators/current", platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console", null));
    }

    @Test
    void iamPlatformEndpointsNeedAPlatformRealmToken() throws Exception {
        // 测试未配置 IAM 路由：通过安全规则后得到路由 404，被拦截则为 401/403。
        String operator = platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console");
        for (String path : List.of("/iam/platform/operators", "/iam/platform/roles", "/iam/platform/access")) {
            assertUnauthenticatedAt(path, null);
            assertUnauthenticatedAt(path, token(KEY, ISSUER, Instant.now().plusSeconds(900)));
            assertEnvelope(call(HttpMethod.GET, path, operator), 404, "request.notFound");
        }
        // Platform Realm 的 JWKS 之外的 OAuth2 端点同样不经网关暴露
        assertEnvelope(call(HttpMethod.POST, "/iam/platform/oauth2/token", operator), 403, "auth.forbidden");
        // 登录相关接口仍是匿名的
        assertEnvelope(call(HttpMethod.POST, "/iam/platform/auth/login", null), 404, "request.notFound");
    }

    @Test
    void tenantTokenNeedsCompleteContext() throws Exception {
        String token = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        var original = SignedJWT.parse(token);
        var incomplete = new SignedJWT(
                original.getHeader(),
                new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                        .claim("tenant_id", null)
                        .build());
        incomplete.sign(new RSASSASigner(KEY));
        assertUnauthenticated(incomplete.serialize());
    }

    @Test
    void internalTokenEndpointIsNotExposedByGateway() throws Exception {
        assertThat(call(HttpMethod.POST, "/iam/oauth2/token", null).status()).isEqualTo(401);
        Response denied = call(
                HttpMethod.POST,
                "/iam/oauth2/token",
                token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertEnvelope(denied, 403, "auth.forbidden");
        assertThat(denied.body().path("userTip").asString()).isEqualTo("没有权限执行该操作");
        // 与 Bearer 默认拒绝处理器一致，保留 RFC 6750 的质询头
        assertThat(denied.wwwAuthenticate()).startsWith("Bearer").contains("error=\"insufficient_scope\"");
    }

    @Test
    void tenantSwitchEndpointsAreReachableWithTheirOwnCredentials() throws Exception {
        // 测试未配置 IAM 路由：通过安全规则后得到路由 404，被拦截则为 401/403。
        Response notFound = call(HttpMethod.POST, "/iam/auth/switch", null);
        assertEnvelope(notFound, 404, "request.notFound");
        assertThat(notFound.body().path("userTip").asString()).isEqualTo("请求的资源不存在");
        assertUnauthenticatedAt("/iam/auth/memberships", null);
        assertEnvelope(
                call(
                        HttpMethod.GET,
                        "/iam/auth/memberships",
                        token(KEY, ISSUER, Instant.now().plusSeconds(900))),
                404,
                "request.notFound");
    }

    @Test
    void iamEndpointsNeedATenantRealmTokenByDefault() throws Exception {
        // 测试未配置 IAM 路由：通过安全规则后得到路由 404，被拦截则为 401/403。
        // 网关没有逐个列出的 iam 接口（如 /auth/access 与以后新增的接口）默认要求 Tenant Realm Token。
        String tenant = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        for (String path : List.of("/iam/auth/access", "/iam/org/departments", "/iam/roles")) {
            assertUnauthenticatedAt(path, null);
            assertUnauthenticatedAt(path, platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console"));
            assertEnvelope(call(HttpMethod.GET, path, tenant), 404, "request.notFound");
        }
    }

    @Test
    void iamInternalEndpointsAreNotExposedEvenWithATenantToken() throws Exception {
        String tenant = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        for (String path : List.of("/iam/internal/manifests", "/iam/oauth2/introspect", "/iam/oauth2/revoke")) {
            assertEnvelope(call(HttpMethod.POST, path, tenant), 403, "auth.forbidden");
        }
    }

    @Test
    void registrationAndCodeLoginEndpointsAreAnonymous() throws Exception {
        // 测试未配置 IAM 路由：通过安全规则后得到路由 404，被拦截则为 401。
        for (String path : List.of(
                "/iam/auth/login/send-code",
                "/iam/auth/code-login",
                "/iam/auth/login/mfa",
                "/iam/auth/register/send-code",
                "/iam/auth/register",
                "/iam/auth/password/forgot/send-code",
                "/iam/auth/password/forgot/verify",
                "/iam/auth/password/forgot/reset")) {
            assertEnvelope(call(HttpMethod.POST, path, null), 404, "request.notFound");
        }
        // 自助改密码需要所在 Realm 的 Access Token。
        for (String path : List.of("/iam/auth/password/change", "/iam/platform/auth/password/change")) {
            assertEnvelope(call(HttpMethod.POST, path, null), 401, "auth.unauthenticated");
        }
    }

    @Test
    void authenticatorBindingIsReachableWithALoginSessionButOtherMfaEndpointsNeedAToken() throws Exception {
        // 绑定接口由 iam 自行校验 Access Token 或登录会话，网关只放行；携带的 Token 无效时仍在网关被拒。
        String expired = token(KEY, ISSUER, Instant.now().minusSeconds(60));
        for (String path : List.of("/iam/auth/mfa/bind/start", "/iam/auth/mfa/bind/confirm")) {
            assertEnvelope(call(HttpMethod.POST, path, null), 404, "request.notFound");
            assertEnvelope(call(HttpMethod.POST, path, expired), 401, "auth.unauthenticated");
        }
        for (String path : List.of("/iam/auth/mfa/recovery-codes/regenerate", "/iam/auth/mfa/unbind")) {
            assertEnvelope(call(HttpMethod.POST, path, null), 401, "auth.unauthenticated");
        }
    }

    @Test
    void operatorLoginSecondStepIsAnonymousButSelfServiceMfaNeedsAPlatformToken() throws Exception {
        // 登录第二步的状态在 iam 登录会话中，由 iam 校验；已登录后的自助管理须带 Platform Token。
        for (String path : List.of(
                "/iam/platform/auth/login/mfa",
                "/iam/platform/auth/login/bind/start",
                "/iam/platform/auth/login/bind/confirm")) {
            assertEnvelope(call(HttpMethod.POST, path, null), 404, "request.notFound");
        }
        for (String path : List.of(
                "/iam/platform/auth/mfa/bind/start",
                "/iam/platform/auth/mfa/bind/confirm",
                "/iam/platform/auth/mfa/replace/start",
                "/iam/platform/auth/mfa/recovery-codes/regenerate",
                "/iam/platform/auth/mfa/unbind")) {
            assertEnvelope(call(HttpMethod.POST, path, null), 401, "auth.unauthenticated");
        }
    }

    @Test
    void declaredServicesDenyInternalPathsAndUndeclaredPrefixes() throws Exception {
        String tenant = token(KEY, ISSUER, Instant.now().plusSeconds(900));
        String platform = platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console");
        for (String path : List.of("/orders/internal", "/orders/internal/secrets", "/iam/internal/secrets")) {
            assertEnvelope(call(HttpMethod.GET, path, tenant), 403, "auth.forbidden");
        }
        assertEnvelope(call(HttpMethod.GET, "/platform/internal/secrets", platform), 403, "auth.forbidden");
        for (String path : List.of("/demo/context", "/unknown/context", "/orders-extra/context")) {
            assertEnvelope(call(HttpMethod.GET, path, tenant), 403, "auth.forbidden");
            assertUnauthenticatedAt(path, null);
        }
    }

    @Test
    void declaredServiceDocsAreAnonymous() {
        assertThat(call(HttpMethod.GET, "/orders/v3/api-docs", null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/platform/v3/api-docs", null).status()).isEqualTo(200);
        assertEnvelope(call(HttpMethod.GET, "/iam/v3/api-docs", null), 404, "request.notFound");
    }

    @Test
    void swaggerGroupsComeFromDeclaredServices() {
        Response response = call(HttpMethod.GET, "/v3/api-docs/swagger-config", null);
        assertThat(response.status()).isEqualTo(200);
        var urls = response.body().path("urls");
        assertThat(urls.size()).isEqualTo(4);
        var groups = new HashMap<String, String>();
        urls.forEach(
                url -> groups.put(url.path("name").asString(), url.path("url").asString()));
        assertThat(groups)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "iam",
                        "/iam/v3/api-docs",
                        "platform",
                        "/platform/v3/api-docs",
                        "orders",
                        "/orders/v3/api-docs",
                        "control",
                        "/control/v3/api-docs"));
    }

    @Test
    void newPlatformServiceUsesPlatformRealmAndOperatorVersion() throws Exception {
        String operator = platformToken(PLATFORM_KEY, PLATFORM_ISSUER, "platform-console");
        assertUnauthenticatedAt("/control/items", null);
        assertUnauthenticatedAt(
                "/control/items", token(KEY, ISSUER, Instant.now().plusSeconds(900)));
        assertEnvelope(call(HttpMethod.GET, "/control/items", operator), 404, "request.notFound");
        assertEnvelope(call(HttpMethod.GET, "/control/internal/secrets", operator), 403, "auth.forbidden");
        assertEnvelope(call(HttpMethod.GET, "/control/v3/api-docs", null), 404, "request.notFound");
        redisTemplate.opsForValue().set(OperatorStateKeys.version(OPERATOR_ID), "2");
        assertUnauthenticatedAt("/control/items", operator);
    }

    @Test
    void missingEmptyOrInvalidServiceConfigurationFailsStartup() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ServiceBinding.class);
        for (String[] properties : List.of(
                new String[] {},
                new String[] {"forge.gateway.services[0].prefix=/webjars", "forge.gateway.services[0].realm=tenant"},
                new String[] {"forge.gateway.services="},
                new String[] {"forge.gateway.services[0].prefix=/orders"},
                new String[] {"forge.gateway.services[0].realm=tenant"},
                new String[] {"forge.gateway.services[0].prefix=/**", "forge.gateway.services[0].realm=tenant"},
                new String[] {"forge.gateway.services[0].prefix=/orders", "forge.gateway.services[0].realm=other"},
                new String[] {
                    "forge.gateway.services[0].prefix=/orders",
                    "forge.gateway.services[0].realm=tenant",
                    "forge.gateway.services[1].prefix=/orders",
                    "forge.gateway.services[1].realm=platform"
                },
                new String[] {"forge.gateway.services[0].prefix=/iam", "forge.gateway.services[0].realm=platform"})) {
            runner.withPropertyValues(properties)
                    .run(context -> assertThat(context).hasFailed());
        }
        runner.withPropertyValues("forge.gateway.services[0].prefix=/orders", "forge.gateway.services[0].realm=tenant")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @EnableConfigurationProperties(GatewayServicesProperties.class)
    static class ServiceBinding {}

    private void assertUnauthenticated(String token) {
        assertUnauthenticatedAt("/orders/context", token);
    }

    private void assertUnauthenticatedAt(String path, String token) {
        Response response = call(HttpMethod.GET, path, token);
        assertEnvelope(response, 401, "auth.unauthenticated");
        assertThat(response.body().path("userTip").asString()).isEqualTo("登录已失效，请重新登录");
        // 与 Bearer 默认入口一致：无 Token 时只有 Bearer，Token 无效时带 invalid_token
        assertThat(response.wwwAuthenticate()).startsWith("Bearer");
        if (token != null) {
            assertThat(response.wwwAuthenticate()).contains("error=\"invalid_token\"");
        }
    }

    private static void assertEnvelope(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().path("code").asString()).isEqualTo(code);
        assertThat(response.body().path("httpStatus").asInt()).isEqualTo(status);
        assertThat(response.traceIdHeader()).hasSize(32);
        assertThat(response.body().path("traceId").asString()).isEqualTo(response.traceIdHeader());
    }

    private int status(String token) {
        return call(HttpMethod.GET, "/orders/context", token).status();
    }

    private Response call(HttpMethod method, String path, String token) {
        var request =
                RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> {
            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            return new Response(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst("X-Trace-Id"),
                    response.getHeaders().getFirst("WWW-Authenticate"),
                    body);
        });
    }

    record Response(int status, String traceIdHeader, String wwwAuthenticate, String rawBody) {
        JsonNode body() {
            return JSON.readTree(rawBody);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    static String platformToken(RSAKey key, String issuer, String audience) throws Exception {
        return platformToken(key, issuer, audience, 1);
    }

    static String platformToken(RSAKey key, String issuer, String audience, Integer version) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(OPERATOR_ID.toString())
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("ver", version)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
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
