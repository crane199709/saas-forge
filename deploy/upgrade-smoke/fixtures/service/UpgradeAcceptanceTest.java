package com.example.retained;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 实际旧/新 JAR 先后连接同一个隔离数据库；数据断言只通过业务 HTTP，迁移历史使用 Flyway 公共接口。 */
class UpgradeAcceptanceTest {
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-000000000011");
    static final UUID MEMBER_B = UUID.fromString("019a0110-0000-7000-8000-000000000013");
    static final UUID READ_ONLY = UUID.fromString("019a0110-0000-7000-8000-000000000023");
    static final String OPS_TOKEN = "isolated-upgrade-ops-token";
    final Path work = Path.of(System.getProperty("upgrade.work"));
    final Map<String, Object> evidence = new LinkedHashMap<>();

    @Test
    void publishedServiceRetainsBusinessDataPermissionsAndMigrationHistoryAfterUpgrade() throws Exception {
        var postgres = SharedContainers.postgres();
        var redis = SharedContainers.redis();
        var configuration = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        configuration.setDatabase(redis.database());
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        var grants = new StringRedisTemplate(factory);
        TestGrants.publishAll(grants, TestTokens.TENANT_ID, TestTokens.MEMBERSHIP_ID);
        TestGrants.publishAll(grants, TENANT_B, MEMBER_B);
        TestGrants.publish(grants, TestTokens.TENANT_ID, READ_ONLY, List.of("order:read"));
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        // TestTokens 的静态初始化提供与现有容器测试相同的 JWKS 接缝，不启动平台或认证 mock filter。
        String tokenA = TestTokens.forTenant(TestTokens.TENANT_ID);
        String tokenB = TestTokens.forMembership(TENANT_B, MEMBER_B);
        String reader = TestTokens.forMembership(TestTokens.TENANT_ID, READ_ONLY);
        var jwks = new String[1];
        TestTokens.register(new org.springframework.test.context.DynamicPropertyRegistry() {
            @Override
            public void add(String name, java.util.function.Supplier<Object> value) {
                if (name.equals("forge.security.jwk-set-uri")) {
                    jwks[0] = (String) value.get();
                }
            }
        });
        var client = RestClient.create("http://127.0.0.1:" + port);
        List<Map<String, Object>> historyBefore;
        String idA;
        String idB;
        try {
            try (var old = start("old", work.resolve("old-service.jar"), postgres, redis, port, jwks[0], client)) {
                var createdA = exchange(
                        client, HttpMethod.POST, "/orders", tokenA, Map.of("title", "升级前采购单", "amount", 128), 201);
                idA = (String) data(createdA).get("id");
                idB = (String) data(exchange(
                                client,
                                HttpMethod.POST,
                                "/orders",
                                tokenB,
                                Map.of("title", "另一租户旧单", "amount", 64),
                                201))
                        .get("id");
                evidence.put(
                        "before",
                        Map.of(
                                "tenantA",
                                data(exchange(client, HttpMethod.GET, "/orders/" + idA, tokenA, null, 200)),
                                "tenantB",
                                data(exchange(client, HttpMethod.GET, "/orders/" + idB, tokenB, null, 200))));
                assertThat(data(exchange(client, HttpMethod.GET, "/greeting", tokenA, null, 200))
                                .get("message"))
                        .as("业务团队对旧版控制器的改动")
                        .asString()
                        .startsWith("existing-business:KEEP-2026:");
                exchange(client, HttpMethod.GET, "/orders/" + idA, tokenB, null, 404);
                exchange(client, HttpMethod.POST, "/orders", reader, Map.of("title", "无创建权限", "amount", 1), 403);
                historyBefore = history(postgres);
                assertThat(historyBefore).hasSize(7);
                evidence.put("historyBefore", historyBefore);
            }
            try (var upgraded = start(
                    "upgraded",
                    work.resolve("upgraded/retained-service/target/retained-service-7.4.0-SNAPSHOT.jar"),
                    postgres,
                    redis,
                    port,
                    jwks[0],
                    client)) {
                var retained = data(exchange(client, HttpMethod.GET, "/orders/" + idA, tokenA, null, 200));
                assertThat(retained.get("title")).isEqualTo("升级前采购单");
                assertThat(((Number) retained.get("amount")).intValue()).isEqualTo(128);
                evidence.put("afterRead", retained);
                assertThat(data(exchange(client, HttpMethod.GET, "/greeting", tokenA, null, 200))
                                .get("message"))
                        .asString()
                        .startsWith("existing-business:KEEP-2026:");
                exchange(
                        client,
                        HttpMethod.PUT,
                        "/orders/" + idA,
                        tokenA,
                        Map.of("title", "升级后继续处理", "amount", 256),
                        200);
                assertThat(data(exchange(client, HttpMethod.GET, "/orders/" + idA, tokenA, null, 200))
                                .get("title"))
                        .isEqualTo("升级后继续处理");
                exchange(client, HttpMethod.POST, "/orders", tokenA, Map.of("title", "升级后新单", "amount", 32), 201);
                assertThat(data(exchange(client, HttpMethod.GET, "/orders/" + idB, tokenB, null, 200))
                                .get("title"))
                        .isEqualTo("另一租户旧单");
                exchange(client, HttpMethod.GET, "/orders/" + idA, tokenB, null, 404);
                exchange(client, HttpMethod.PUT, "/orders/" + idA, tokenB, Map.of("title", "越权修改", "amount", 1), 404);
                exchange(client, HttpMethod.POST, "/orders", reader, Map.of("title", "无创建权限", "amount", 1), 403);
                exchange(client, HttpMethod.GET, "/orders", null, null, 401);
                exchange(client, HttpMethod.POST, "/orders", tokenA, Map.of("title", " ", "amount", -1), 400);
                // 此 SDK 公共运维入口会实际查询死信表，漏迁移不能被健康检查掩盖。
                exchange(client, HttpMethod.GET, "/internal/ops/dead-events", OPS_TOKEN, null, 200);
                exchange(client, HttpMethod.GET, "/internal/ops/dead-events", tokenA, null, 401);
                var historyAfter = history(postgres);
                assertThat(historyAfter.subList(0, 7)).isEqualTo(historyBefore);
                assertThat(historyAfter.size()).isGreaterThan(7);
                evidence.put("historyAfter", historyAfter);
                evidence.put("result", "passed");
            }
        } finally {
            factory.destroy();
            Files.writeString(
                    work.resolve("http-evidence.json"),
                    JsonMapper.builder()
                            .build()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(evidence));
        }
    }

    private Running start(
            String phase,
            Path jar,
            SharedContainers.Postgres postgres,
            SharedContainers.Redis redis,
            int port,
            String jwks,
            RestClient client)
            throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms64m",
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                jar.toString(),
                "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.config.import=",
                "--forge.mq.enabled=false",
                "--forge.manifest.enabled=false",
                "--forge.tenant.write-guard.enabled=false",
                "--spring.data.redis.database=" + redis.database()));
        var builder = new ProcessBuilder(command);
        var env = builder.environment();
        String path = env.get("PATH");
        String home = env.get("HOME");
        env.clear();
        env.put("PATH", path);
        env.put("HOME", home);
        env.putAll(Map.of(
                "FORGE_DB_URL",
                postgres.getJdbcUrl(),
                "FORGE_DB_USER",
                "demo_app",
                "FORGE_DB_PASSWORD",
                "demo_app",
                "FORGE_DB_MIGRATOR_USER",
                "demo_migrator",
                "FORGE_DB_MIGRATOR_PASSWORD",
                "demo_migrator",
                "FORGE_DB_SCHEMA",
                "demo",
                "FORGE_SERVICE_PORT",
                String.valueOf(port),
                "FORGE_NACOS_ADDR",
                "127.0.0.1:1"));
        env.putAll(Map.of(
                "FORGE_REDIS_HOST",
                redis.getHost(),
                "FORGE_REDIS_PORT",
                String.valueOf(redis.getMappedPort(6379)),
                "FORGE_SECURITY_ISSUER",
                TestTokens.ISSUER,
                "FORGE_SECURITY_JWK_SET_URI",
                jwks,
                "FORGE_ROCKETMQ_ENDPOINTS",
                "127.0.0.1:1",
                "FORGE_IAM_URI",
                "http://127.0.0.1:1",
                "FORGE_MANIFEST_TOKEN",
                "isolated-manifest-token",
                "FORGE_OPS_TOKEN",
                OPS_TOKEN));
        Path log = work.resolve("runtime-" + phase + ".log");
        Process process =
                builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var running = new Running(process);
        try {
            for (int attempt = 0; attempt < 120; attempt++) {
                assertThat(process.isAlive()).as("%s 启动失败，见 %s", phase, log).isTrue();
                try {
                    client.get().uri("/actuator/health").retrieve().toBodilessEntity();
                    return running;
                } catch (Exception waiting) {
                    Thread.sleep(500);
                }
            }
            throw new AssertionError(phase + " 启动超时：" + log);
        } catch (Throwable failure) {
            running.close();
            throw failure;
        }
    }

    private List<Map<String, Object>> history(SharedContainers.Postgres postgres) {
        return Arrays.stream(Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), "demo_migrator", "demo_migrator")
                        .schemas("demo")
                        .defaultSchema("demo")
                        .placeholders(Map.of("serviceSchema", "demo"))
                        .load()
                        .info()
                        .applied())
                .map(info -> Map.<String, Object>of(
                        "version",
                        info.getVersion().toString(),
                        "script",
                        info.getScript(),
                        "checksum",
                        info.getChecksum(),
                        "installedOn",
                        info.getInstalledOn().toInstant().toString(),
                        "installedBy",
                        info.getInstalledBy()))
                .toList();
    }

    private Map<?, ?> exchange(
            RestClient client, HttpMethod method, String uri, String token, Object body, int status) {
        var request = client.method(method).uri(uri);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((sent, response) -> {
            var result = response.bodyTo(Map.class);
            assertThat(response.getStatusCode().value())
                    .as("%s %s 返回 %s", method, uri, result)
                    .isEqualTo(status);
            return result;
        });
    }

    private Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    record Running(Process process) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
