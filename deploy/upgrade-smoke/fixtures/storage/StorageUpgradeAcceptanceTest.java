package com.example.retained;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

/** 实际发布旧 SDK 与候选在同一隔离数据库和 S3 上通过业务 HTTP 验收。 */
@Testcontainers
class StorageUpgradeAcceptanceTest {
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-000000000011");
    static final UUID MEMBER_B = UUID.fromString("019a0110-0000-7000-8000-000000000013");
    static final UUID OTHER_MEMBER = UUID.fromString("019a0110-0000-7000-8000-000000000023");
    static final String BUCKET = "storage-upgrade";
    static final String ACCESS = "storage-upgrade";
    static final String SECRET = "isolated-storage-upgrade-secret";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>("pgsty/silo:RELEASE.2026-10-18T00-00-00Z")
            .withCommand("silo", "server", "/data")
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    final Path work = Path.of(System.getProperty("upgrade.work"));
    final Map<String, Object> evidence = new LinkedHashMap<>();
    RestClient client;
    String token;
    String tokenB;
    String other;

    @Test
    void publishedFilesAndReferencesSurviveIncrementalUpgrade() throws Exception {
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
        TestGrants.publish(grants, TestTokens.TENANT_ID, OTHER_MEMBER, List.of("order:read"));
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        tokenB = TestTokens.forMembership(TENANT_B, MEMBER_B);
        other = TestTokens.forMembership(TestTokens.TENANT_ID, OTHER_MEMBER);
        var jwks = new String[1];
        TestTokens.register(new org.springframework.test.context.DynamicPropertyRegistry() {
            @Override
            public void add(String name, java.util.function.Supplier<Object> value) {
                if (name.equals("forge.security.jwk-set-uri")) {
                    jwks[0] = (String) value.get();
                }
            }
        });
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        client = RestClient.create("http://127.0.0.1:" + port);
        try (var storage = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .build()) {
            storage.createBucket(request -> request.bucket(BUCKET));
        }
        try {
            String objectId;
            String unknownId;
            String deletedRefId;
            String pendingId;
            String objectB;
            String documentId;
            String documentB;
            String deletedDocument;
            String newObject;
            var beforeObjects = new LinkedHashMap<String, Map<?, ?>>();
            Map<?, ?> beforeDocument;
            List<Map<String, Object>> beforeHistory;
            long beforeBytes;
            try (var old = start("old", work.resolve("old-service.jar"), postgres, redis, port, jwks[0], client)) {
                objectId = upload(token, "原业务附件内容");
                documentId = bind(token, objectId, "升级前业务文档");
                unknownId = upload(token, "未知用途旧文件");
                deletedRefId = upload(token, "逻辑删除业务仍有引用");
                deletedDocument = bind(token, deletedRefId, "逻辑删除文档");
                exchange(HttpMethod.DELETE, "/documents/" + deletedDocument, token, null, 200);
                objectB = upload(tokenB, "另一租户原文件");
                documentB = bind(tokenB, objectB, "另一租户文档");
                pendingId = put(token, "升级前上传但尚未确认");
                exchange(HttpMethod.GET, "/documents/objects/" + pendingId, token, null, 404);
                for (String id : List.of(objectId, unknownId, deletedRefId)) {
                    beforeObjects.put(id, data(exchange(HttpMethod.GET, "/documents/objects/" + id, token, null, 200)));
                }
                beforeObjects.put(
                        objectB, data(exchange(HttpMethod.GET, "/documents/objects/" + objectB, tokenB, null, 200)));
                byte[] original = download("/documents/" + documentId + "/download", token);
                assertThat(original).isEqualTo(bytes("原业务附件内容"));
                Files.write(work.resolve("before-file.txt"), original);
                evidence.put("oldFileSha256", sha256(original));
                assertThat(download("/documents/objects/" + unknownId + "/download", token))
                        .isEqualTo(bytes("未知用途旧文件"));
                beforeDocument = data(exchange(HttpMethod.GET, "/documents/" + documentId, token, null, 200));
                beforeBytes = usedBytes();
                assertThat(beforeBytes)
                        .isEqualTo(bytes("原业务附件内容").length
                                + bytes("未知用途旧文件").length
                                + bytes("逻辑删除业务仍有引用").length
                                + bytes("升级前上传但尚未确认").length);
                evidence.put("objectsBefore", beforeObjects);
                evidence.put("documentBefore", beforeDocument);
                evidence.put("pendingObjectId", pendingId);
                evidence.put("unknownObjectId", unknownId);
                evidence.put("deletedReferenceObjectId", deletedRefId);
                evidence.put("usedBytesBefore", beforeBytes);
                beforeHistory = history(postgres);
                assertThat(beforeHistory).hasSize(9);
                evidence.put("historyBefore", beforeHistory);
                exchange(HttpMethod.GET, "/documents/" + documentId, tokenB, null, 404);
                exchange(
                        HttpMethod.POST, "/documents/upload", other, Map.of("filename", "blocked.txt", "size", 1), 403);
            }
            Path jar = work.resolve("upgraded/retained-service/target/retained-service-7.4.0-SNAPSHOT.jar");
            try (var upgraded = start("upgraded", jar, postgres, redis, port, jwks[0], client)) {
                for (var entry : beforeObjects.entrySet()) {
                    String bearer = entry.getKey().equals(objectB) ? tokenB : token;
                    assertThat(data(exchange(
                                    HttpMethod.GET, "/documents/objects/" + entry.getKey(), bearer, null, 200)))
                            .isEqualTo(entry.getValue());
                }
                assertThat(data(exchange(HttpMethod.GET, "/documents/" + documentId, token, null, 200)))
                        .isEqualTo(beforeDocument);
                assertThat(download("/documents/" + documentId + "/download", token))
                        .isEqualTo(bytes("原业务附件内容"));
                assertThat(download("/documents/" + documentB + "/download", tokenB))
                        .isEqualTo(bytes("另一租户原文件"));
                // 归类包含逻辑删除引用；未知文件虽有同样扩展名，仍不能按附件下载或绑定。
                exchange(HttpMethod.GET, "/documents/" + deletedDocument, token, null, 404);
                assertThat(download("/documents/objects/" + deletedRefId + "/download", token))
                        .isEqualTo(bytes("逻辑删除业务仍有引用"));
                assertThat(exchange(HttpMethod.GET, "/documents/objects/" + unknownId + "/download", token, null, 400)
                                .get("code"))
                        .isEqualTo("storage.purposeMismatch");
                assertThat(exchange(
                                        HttpMethod.POST,
                                        "/documents",
                                        token,
                                        Map.of("objectId", unknownId, "title", "禁止猜测用途"),
                                        400)
                                .get("code"))
                        .isEqualTo("storage.purposeMismatch");
                assertThat(usedBytes()).isEqualTo(beforeBytes);
                for (String id : List.of(objectId, unknownId, deletedRefId, pendingId)) {
                    var lifecycle = lifecycle(id);
                    assertThat(lifecycle.get("status")).isEqualTo(id.equals(pendingId) ? "PENDING" : "STORED");
                    assertThat(lifecycle.get("retentionExpiresAt")).isNull();
                    assertThat(lifecycle.get("expiredAt")).isNull();
                    assertThat(lifecycle.get("purgeAfter")).isNull();
                }
                exchange(HttpMethod.POST, "/documents/objects/" + pendingId + "/complete", tokenB, null, 404);
                exchange(HttpMethod.POST, "/documents/objects/" + pendingId + "/complete", token, null, 200);
                assertThat(lifecycle(pendingId).get("status")).isEqualTo("STORED");
                assertThat(lifecycle(pendingId).get("retentionExpiresAt")).isNull();
                exchange(HttpMethod.GET, "/documents/objects/" + pendingId + "/download", token, null, 400);
                evidence.put("existingUnknownDownloadRejected", true);
                evidence.put("deletedReferenceStillClassified", true);
                evidence.put("oldPendingContinuedWithoutRetentionBackfill", lifecycle(pendingId));
                for (String path : List.of(
                        "/documents/" + documentId,
                        "/documents/" + documentId + "/download",
                        "/documents/objects/" + objectId,
                        "/documents/objects/" + objectId + "/download",
                        "/documents/objects/" + objectId + "/lifecycle")) {
                    exchange(HttpMethod.GET, path, tokenB, null, 404);
                }
                exchange(HttpMethod.GET, "/documents/" + documentId, null, null, 401);
                exchange(
                        HttpMethod.POST, "/documents/upload", other, Map.of("filename", "blocked.txt", "size", 1), 403);
                assertThat(exchange(
                                        HttpMethod.POST,
                                        "/documents/upload",
                                        token,
                                        Map.of("filename", "file.exe", "size", 1),
                                        400)
                                .get("code"))
                        .isEqualTo("storage.extensionNotAllowed");
                assertThat(exchange(
                                        HttpMethod.POST,
                                        "/documents/upload",
                                        token,
                                        Map.of("filename", "file.txt", "size", 1025),
                                        400)
                                .get("code"))
                        .isEqualTo("storage.fileTooLarge");
                assertThat(usedBytes()).isEqualTo(beforeBytes);
                newObject = upload(token, "候选新文件内容");
                var active = lifecycle(newObject);
                var deadline = java.time.OffsetDateTime.parse((String) active.get("retentionExpiresAt"));
                assertThat(deadline).isAfter(java.time.OffsetDateTime.now().plusDays(29));
                assertThat(deadline).isBefore(java.time.OffsetDateTime.now().plusDays(31));
                var ticket = data(
                        exchange(HttpMethod.GET, "/documents/objects/" + newObject + "/download", token, null, 200));
                long totalBytes = beforeBytes + bytes("候选新文件内容").length;
                assertThat(usedBytes()).isEqualTo(totalBytes);
                exchange(HttpMethod.DELETE, "/documents/objects/" + newObject, token, null, 200);
                var expired = lifecycle(newObject);
                assertThat(expired.get("status")).isEqualTo("EXPIRED");
                assertThat(java.time.OffsetDateTime.parse((String) expired.get("purgeAfter")))
                        .isEqualTo(java.time.OffsetDateTime.parse((String) expired.get("expiredAt"))
                                .plusDays(7));
                assertThat(usedBytes()).isEqualTo(totalBytes);
                exchange(HttpMethod.GET, "/documents/objects/" + newObject + "/download", token, null, 404);
                exchange(HttpMethod.POST, "/documents/objects/" + newObject + "/complete", token, null, 404);
                assertThat(fetch((String) ticket.get("url"))).isEqualTo(bytes("候选新文件内容"));
                var restored = data(
                        exchange(HttpMethod.POST, "/documents/objects/" + newObject + "/restore", token, null, 200));
                assertThat(restored.get("status")).isEqualTo("STORED");
                assertThat(restored.get("expiredAt")).isNull();
                assertThat(download("/documents/objects/" + newObject + "/download", token))
                        .isEqualTo(bytes("候选新文件内容"));
                String nextDocument = bind(token, newObject, "升级后新文档");
                assertThat(data(exchange(HttpMethod.GET, "/documents/" + nextDocument, token, null, 200))
                                .get("title"))
                        .isEqualTo("升级后新文档");
                evidence.put("newObjectLifecycle", active);
                evidence.put("expiredLifecycle", expired);
                evidence.put("restoredLifecycle", restored);
                evidence.put("usedBytesAfter", usedBytes());
                byte[] retained = download("/documents/" + documentId + "/download", token);
                Files.write(work.resolve("retained-file.txt"), retained);
                evidence.put("retainedFileSha256", sha256(retained));
                var afterHistory = history(postgres);
                assertThat(afterHistory).hasSize(15);
                assertThat(afterHistory.subList(0, 9)).isEqualTo(beforeHistory);
                evidence.put("historyAfter", afterHistory);
                evidence.put(
                        "documentAfter", data(exchange(HttpMethod.GET, "/documents/" + documentId, token, null, 200)));
            }
            try (var restarted = start("restarted", jar, postgres, redis, port, jwks[0], client)) {
                assertThat(history(postgres)).isEqualTo(evidence.get("historyAfter"));
                assertThat(download("/documents/" + documentId + "/download", token))
                        .isEqualTo(bytes("原业务附件内容"));
                assertThat(lifecycle(objectId).get("retentionExpiresAt")).isNull();
                assertThat(lifecycle(newObject).get("status")).isEqualTo("STORED");
                exchange(HttpMethod.GET, "/documents/objects/" + unknownId + "/download", token, null, 400);
                evidence.put("restartHistoryUnchanged", true);
            }
            evidence.put("result", "passed");
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

    private String bind(String bearer, String objectId, String title) {
        return (String)
                data(exchange(HttpMethod.POST, "/documents", bearer, Map.of("objectId", objectId, "title", title), 201))
                        .get("id");
    }

    private Map<?, ?> lifecycle(String id) {
        return data(exchange(HttpMethod.GET, "/documents/objects/" + id + "/lifecycle", token, null, 200));
    }

    private long usedBytes() {
        return ((Number) data(exchange(HttpMethod.GET, "/documents/usage", token, null, 200))
                        .get("usedBytes"))
                .longValue();
    }

    private byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String upload(String bearer, String contents) throws Exception {
        String id = put(bearer, contents);
        exchange(HttpMethod.POST, "/documents/objects/" + id + "/complete", bearer, null, 200);
        return id;
    }

    private String put(String bearer, String contents) throws Exception {
        byte[] bytes = contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var ticket = data(exchange(
                HttpMethod.POST,
                "/documents/upload",
                bearer,
                Map.of("filename", "same-extension.txt", "contentType", "text/plain", "size", bytes.length),
                201));
        var upload = HttpRequest.newBuilder(URI.create((String) ticket.get("url")));
        ((Map<?, ?>) ticket.get("headers")).forEach((name, value) -> upload.header(name.toString(), value.toString()));
        assertThat(HTTP.send(
                                upload.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        String id = (String) ticket.get("objectId");
        return id;
    }

    private byte[] download(String path, String bearer) throws Exception {
        var ticket = data(exchange(HttpMethod.GET, path, bearer, null, 200));
        return fetch((String) ticket.get("url"));
    }

    private byte[] fetch(String url) throws Exception {
        var response =
                HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
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
                "--forge.business-zone=Asia/Shanghai",
                "--forge.scheduling.iam-uri=" + TestReferenceData.uri(),
                "--forge.scheduling.token=" + TestReferenceData.TOKEN,
                "--forge.storage.endpoint=" + endpoint(),
                "--forge.storage.bucket=" + BUCKET,
                "--forge.storage.access-key=" + ACCESS,
                "--forge.storage.secret-key=" + SECRET,
                "--forge.manifest.enabled=false",
                "--forge.tenant.write-guard.enabled=false",
                "--spring.data.redis.database=" + redis.database()));
        evidence.put(
                phase + "Target",
                Map.of(
                        "jdbcUrl",
                        postgres.getJdbcUrl(),
                        "schema",
                        "demo",
                        "runtimeUser",
                        "demo_app",
                        "migrationUser",
                        "demo_migrator",
                        "redisDatabase",
                        redis.database(),
                        "s3Endpoint",
                        endpoint(),
                        "bucket",
                        BUCKET));
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
                "isolated-storage-ops"));
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

    private Map<?, ?> exchange(HttpMethod method, String uri, String bearer, Object body, int status) {
        var request = client.method(method).uri(uri);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
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

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9000);
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
