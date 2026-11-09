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
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 网关日志与 Servlet 服务一致，带上 traceId 与 Token 中的租户、Membership；响应式链路换线程后同样保留。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
class GatewayLogContextTest {
    static final String ISSUER = "http://tenant-realm.test";
    static final UUID TENANT_ID = UUID.fromString("019a0110-0000-7000-8000-00000000000c");
    static final UUID MEMBERSHIP_ID = UUID.fromString("019a0110-0000-7000-8000-00000000000d");

    static final SharedContainers.Redis redis = SharedContainers.redis();

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
    static void gateway(DynamicPropertyRegistry registry) {
        // 端口 1 无服务监听：下游不可用由网关异常处理记录日志
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "unavailable");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/demo/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> "http://127.0.0.1:1");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.security.issuer", () -> ISSUER);
        registry.add(
                "forge.security.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    /** 在其他线程上记录日志的网关内处理，验证 MDC 随 Reactor context 跨线程。 */
    @TestConfiguration
    static class LoggingEndpoint {
        @Bean
        RouterFunction<ServerResponse> loggingRoute() {
            return RouterFunctions.route()
                    .GET(
                            "/demo/log",
                            request -> Mono.fromRunnable(() -> LoggerFactory.getLogger(GatewayLogContextTest.class)
                                            .info("probe on worker thread"))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then(ServerResponse.ok().build()))
                    .build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void publishActiveState() {
        redisTemplate.opsForValue().set(MembershipStateKeys.version(TENANT_ID, MEMBERSHIP_ID), "1");
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(TENANT_ID), "active");
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantIpAllowlist(TENANT_ID), "*");
    }

    @Test
    void logsInsideTheChainCarryTraceTenantAndMembership(CapturedOutput output) throws Exception {
        String traceId = call("/demo/log");
        assertThat(lineWith(output, "probe on worker thread"))
                .contains(traceId, TENANT_ID.toString(), MEMBERSHIP_ID.toString());
    }

    @Test
    void errorHandlerLogsCarryTraceTenantAndMembership(CapturedOutput output) throws Exception {
        String traceId = call("/demo/unavailable");
        assertThat(lineWith(output, "Upstream unavailable for GET /demo/unavailable"))
                .contains(traceId, TENANT_ID.toString(), MEMBERSHIP_ID.toString());
    }

    private static String lineWith(CapturedOutput output, String text) {
        return output.getAll()
                .lines()
                .filter(line -> line.contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line containing " + text));
    }

    /** 返回本次请求的 traceId。 */
    private String call(String path) throws Exception {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri(path)
                .header("Authorization", "Bearer " + token())
                .exchange((request, response) -> response.getHeaders().getFirst("X-Trace-Id"));
    }

    private static String token() throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("019a0110-0000-7000-8000-000000000002")
                .audience("tenant-app")
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", TENANT_ID.toString())
                .claim("membership_id", MEMBERSHIP_ID.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
