package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/** 真实历史/候选 JAR 在同一隔离库和 S3 中先后运行，任务、订单、文件仅经公开 HTTP 验证。 */
@Testcontainers
class IoUpgradeAcceptanceTest {
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-000000000011");
    static final UUID MEMBER_B = UUID.fromString("019a0110-0000-7000-8000-000000000013");
    static final UUID OTHER_MEMBER = UUID.fromString("019a0110-0000-7000-8000-000000000023");
    static final String BUCKET = "io-upgrade";
    static final String ACCESS = "io-upgrade";
    static final String SECRET = "isolated-io-upgrade-secret";
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
    void historicalIoTasksFilesBusinessDataAndPendingConfirmationSurviveIncrementalUpgrade() throws Exception {
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
        TestGrants.publishAll(grants, TestTokens.TENANT_ID, OTHER_MEMBER);
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
        var beforeTasks = new LinkedHashMap<String, Map<?, ?>>();
        List<Map<String, Object>> beforeHistory;
        String oldOrder;
        String exportId;
        String failedId;
        String pendingId;
        byte[] oldExport;
        byte[] oldReport;
        try {
            try (var old = start("old", work.resolve("old-service.jar"), postgres, redis, port, jwks[0])) {
                oldOrder = (String) data(exchange(
                                HttpMethod.POST, "/orders", token, Map.of("title", "升级前手工订单", "amount", 128), 201))
                        .get("id");
                String imported = submit("升级前导入订单", 64);
                await(imported, "AWAITING_CONFIRM");
                exchange(HttpMethod.POST, "/orders/import/" + imported + "/confirm", token, null, 200);
                beforeTasks.put(imported, await(imported, "SUCCEEDED"));
                assertThat(beforeTasks.get(imported).get("rowCount")).isEqualTo(1);
                failedId = submit("非法金额订单", -1);
                beforeTasks.put(failedId, await(failedId, "FAILED"));
                oldReport = download(failedId);
                Files.write(work.resolve("before-error-report.xlsx"), oldReport);
                assertWorkbookContains(oldReport, "错误原因", "非法金额订单");
                exportId = (String) data(exchange(HttpMethod.POST, "/orders/export", token, null, 201))
                        .get("id");
                beforeTasks.put(exportId, await(exportId, "SUCCEEDED"));
                oldExport = download(exportId);
                Files.write(work.resolve("before-export.xlsx"), oldExport);
                assertWorkbookContains(oldExport, "升级前手工订单", "升级前导入订单");
                pendingId = submit("跨版本待确认订单", 96);
                beforeTasks.put(pendingId, await(pendingId, "AWAITING_CONFIRM"));
                assertThat(orders()).hasSize(2);
                evidence.put("tasksBefore", beforeTasks);
                evidence.put("ordersBefore", orders());
                evidence.put("oldExportSha256", sha256(oldExport));
                evidence.put("oldErrorReportSha256", sha256(oldReport));
                beforeHistory = history(postgres);
                assertThat(beforeHistory).hasSize(14);
                evidence.put("historyBefore", beforeHistory);
            }
            try (var upgraded = start(
                    "upgraded",
                    work.resolve("upgraded/target/retained-io-service-7.5.0-SNAPSHOT.jar"),
                    postgres,
                    redis,
                    port,
                    jwks[0])) {
                var tasks = (List<?>)
                        exchange(HttpMethod.GET, "/io/tasks", token, null, 200).get("data");
                assertThat(tasks).hasSize(4);
                for (var entry : beforeTasks.entrySet()) {
                    var task = data(exchange(HttpMethod.GET, "/io/tasks/" + entry.getKey(), token, null, 200));
                    for (String field : List.of(
                            "id",
                            "type",
                            "status",
                            "membershipId",
                            "rowCount",
                            "errorCode",
                            "failureReason",
                            "heartbeatAt",
                            "createdAt",
                            "finishedAt",
                            "resultAvailable")) {
                        assertThat(task.get(field))
                                .as("保留任务 %s 的 %s", entry.getKey(), field)
                                .isEqualTo(entry.getValue().get(field));
                    }
                    assertThat(task.get("importModel")).isNull();
                    assertThat(((Number) task.get("snapshotCount")).longValue()).isZero();
                    assertThat(task.get("snapshotExpiresAt")).isNull();
                    assertThat(task.get("snapshotAvailable")).isEqualTo(false);
                    assertThat(task.get("rollbackStatus")).isEqualTo("NONE");
                    for (String field : List.of("rollbackAttempts", "restoredCount", "skippedCount", "failedCount")) {
                        assertThat(((Number) task.get(field)).longValue()).isZero();
                    }
                    exchange(HttpMethod.GET, "/io/tasks/" + entry.getKey(), tokenB, null, 404);
                    exchange(HttpMethod.GET, "/io/tasks/" + entry.getKey(), other, null, 404);
                }
                evidence.put(
                        "tasksAfterMigration",
                        exchange(HttpMethod.GET, "/io/tasks", token, null, 200).get("data"));
                var retainedExport = download(exportId);
                assertThat(retainedExport).isEqualTo(oldExport);
                Files.write(work.resolve("retained-export.xlsx"), retainedExport);
                var retainedReport = download(failedId);
                assertThat(retainedReport).isEqualTo(oldReport);
                Files.write(work.resolve("retained-error-report.xlsx"), retainedReport);
                exchange(HttpMethod.GET, "/io/tasks/" + exportId + "/download", tokenB, null, 404);
                exchange(HttpMethod.GET, "/io/tasks", null, null, 401);
                assertThat(data(exchange(HttpMethod.GET, "/orders/" + oldOrder, token, null, 200))
                                .get("title"))
                        .isEqualTo("升级前手工订单");
                exchange(HttpMethod.GET, "/orders/" + oldOrder, tokenB, null, 404);
                assertThat(orders()).hasSize(2);
                exchange(HttpMethod.POST, "/orders/import/" + pendingId + "/confirm", other, null, 403);
                exchange(HttpMethod.POST, "/orders/import/" + pendingId + "/confirm", tokenB, null, 404);
                // 历史 import_model 为 NULL，继续由实际业务确认路由选择模型，不能凭迁移伪造模型名。
                exchange(HttpMethod.POST, "/orders/import/" + pendingId + "/confirm", token, null, 200);
                var confirmed = await(pendingId, "SUCCEEDED");
                assertThat(confirmed.get("importModel")).isNull();
                assertThat(confirmed.get("snapshotAvailable")).isEqualTo(false);
                exchange(HttpMethod.POST, "/orders/import/" + pendingId + "/confirm", token, null, 409);
                String next = submit("升级后新导入订单", 32);
                await(next, "AWAITING_CONFIRM");
                exchange(HttpMethod.POST, "/orders/import/" + next + "/confirm", token, null, 200);
                assertThat(await(next, "SUCCEEDED").get("importModel"))
                        .isEqualTo("io.saasforge.demo.order.model.OrderImportRow");
                assertThat(orders()).hasSize(4);
                String nextExport = (String) data(exchange(HttpMethod.POST, "/orders/export", token, null, 201))
                        .get("id");
                await(nextExport, "SUCCEEDED");
                var newExport = download(nextExport);
                Files.write(work.resolve("new-export.xlsx"), newExport);
                assertWorkbookContains(newExport, "升级前手工订单", "升级前导入订单", "跨版本待确认订单", "升级后新导入订单");
                var afterHistory = history(postgres);
                assertThat(afterHistory).hasSize(18);
                assertThat(afterHistory.subList(0, 14)).isEqualTo(beforeHistory);
                evidence.put("historyAfter", afterHistory);
                evidence.put("ordersAfter", orders());
                evidence.put("continuedConfirmation", confirmed);
                evidence.put(
                        "tasksAfter",
                        exchange(HttpMethod.GET, "/io/tasks", token, null, 200).get("data"));
                evidence.put("retainedExportSha256", sha256(download(exportId)));
                evidence.put("retainedErrorReportSha256", sha256(download(failedId)));
            }
            // 第二次启动同库：迁移不能再次执行，也不能把已完成任务重置为等待确认。
            try (var restarted = start(
                    "restarted",
                    work.resolve("upgraded/target/retained-io-service-7.5.0-SNAPSHOT.jar"),
                    postgres,
                    redis,
                    port,
                    jwks[0])) {
                assertThat(history(postgres)).isEqualTo(evidence.get("historyAfter"));
                assertThat(data(exchange(HttpMethod.GET, "/io/tasks/" + pendingId, token, null, 200))
                                .get("status"))
                        .isEqualTo("SUCCEEDED");
                assertThat(orders()).hasSize(4);
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

    private String submit(String title, int amount) throws Exception {
        var template = data(exchange(HttpMethod.GET, "/orders/import/template", token, null, 200));
        byte[] bytes;
        try (var book = new XSSFWorkbook(
                        new ByteArrayInputStream(Base64.getDecoder().decode((String) template.get("base64"))));
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheetAt(0);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(title);
            row.createCell(1).setCellValue(amount);
            row.createCell(2).setCellValue("线上");
            book.write(output);
            bytes = output.toByteArray();
        }
        var ticket = data(exchange(
                HttpMethod.POST,
                "/orders/import/upload",
                token,
                Map.of("filename", "orders.xlsx", "contentType", XLSX, "size", bytes.length),
                201));
        var upload = HttpRequest.newBuilder(URI.create((String) ticket.get("url")));
        ((Map<?, ?>) ticket.get("headers")).forEach((name, value) -> upload.header(name.toString(), value.toString()));
        assertThat(HTTP.send(
                                upload.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        return (String) data(exchange(
                        HttpMethod.POST, "/orders/import", token, Map.of("objectId", ticket.get("objectId")), 201))
                .get("id");
    }

    private Map<?, ?> await(String id, String status) throws Exception {
        Map<?, ?> task = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            task = data(exchange(HttpMethod.GET, "/io/tasks/" + id, token, null, 200));
            if (status.equals(task.get("status"))) {
                return task;
            }
            if (List.of("SUCCEEDED", "FAILED").contains(task.get("status"))) {
                break;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("任务未进入 " + status + ": " + task);
    }

    private byte[] download(String id) throws Exception {
        var ticket = data(exchange(HttpMethod.GET, "/io/tasks/" + id + "/download", token, null, 200));
        var response = HTTP.send(
                HttpRequest.newBuilder(URI.create((String) ticket.get("url"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private void assertWorkbookContains(byte[] bytes, String... expected) throws Exception {
        var cells = new ArrayList<String>();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (var row : workbook.getSheetAt(0)) {
                for (var cell : row) {
                    cells.add(cell.toString());
                }
            }
        }
        assertThat(cells).contains(expected);
    }

    private List<?> orders() {
        return (List<?>) exchange(HttpMethod.GET, "/orders", token, null, 200).get("data");
    }

    private Running start(
            String phase,
            Path jar,
            SharedContainers.Postgres postgres,
            SharedContainers.Redis redis,
            int port,
            String jwks)
            throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms64m",
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                jar.toString(),
                "--server.port=" + port,
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=demo_app",
                "--spring.datasource.password=demo_app",
                "--spring.flyway.password=demo_migrator",
                "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.config.import=",
                "--forge.mq.enabled=false",
                "--forge.manifest.enabled=false",
                "--forge.field-masking.enabled=false",
                "--forge.tenant.write-guard.enabled=false",
                "--forge.business-zone=Asia/Shanghai",
                "--forge.security.issuer=" + TestTokens.ISSUER,
                "--forge.security.jwk-set-uri=" + jwks,
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.database=" + redis.database(),
                "--forge.reference-data.iam-uri=" + TestReferenceData.uri(),
                "--forge.reference-data.token=" + TestReferenceData.TOKEN,
                "--forge.scheduling.iam-uri=" + TestReferenceData.uri(),
                "--forge.scheduling.token=" + TestReferenceData.TOKEN,
                "--forge.storage.endpoint=" + endpoint(),
                "--forge.storage.bucket=" + BUCKET,
                "--forge.storage.access-key=" + ACCESS,
                "--forge.storage.secret-key=" + SECRET,
                "--forge.ops.token=isolated-io-ops-token"));
        var builder = new ProcessBuilder(command);
        var env = builder.environment();
        String path = env.get("PATH");
        String home = env.get("HOME");
        env.clear();
        env.put("PATH", path);
        env.put("HOME", home);
        env.put("FORGE_MANIFEST_TOKEN", "isolated-io-manifest-token");
        env.put("FORGE_OPS_TOKEN", "isolated-io-ops-token");
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
