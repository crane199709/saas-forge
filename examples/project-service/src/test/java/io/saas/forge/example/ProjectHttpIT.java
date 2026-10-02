package io.saas.forge.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectHttpIT {
    static final String ID = "018f5f2a-7b3c-7def-8123-456789abcdef";
    static final String A = "018f5f2a-7b3c-7def-8123-456789abcdea";
    static final String B = "018f5f2a-7b3c-7def-8123-456789abcdeb";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18")
            .withCopyFileToContainer(MountableFile.forHostPath("deploy/bootstrap.sql"), "/docker-entrypoint-initdb.d/01-example.sql");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1").withExposedPorts(6379);
    @Container static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.0.0");
    @Container static final GenericContainer<?> COLLECTOR = new GenericContainer<>("otel/opentelemetry-collector:0.119.0")
            .withExposedPorts(4318).withCopyToContainer(org.testcontainers.images.builder.Transferable.of("""
                receivers:
                  otlp:
                    protocols:
                      http:
                        endpoint: 0.0.0.0:4318
                exporters:
                  debug:
                    verbosity: detailed
                service:
                  pipelines:
                    traces:
                      receivers: [otlp]
                      exporters: [debug]
                """), "/etc/otelcol/test.yaml").withCommand("--config=/etc/otelcol/test.yaml");
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static ConfigurableApplicationContext app;
    static HttpServer jwks;
    static RSAKey signingKey;
    static String base;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            sql.execute("ALTER ROLE project_migrator PASSWORD 'test-migrator'");
            sql.execute("ALTER ROLE project_app PASSWORD 'test-app'");
        }
        Flyway.configure().dataSource(databaseUrl(), "project_migrator", "test-migrator")
                .locations("classpath:db/migration").load().migrate();
        signingKey = new RSAKeyGenerator(2048).keyID("example-test").algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE).generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        jwks.start();
        // 受控认证设施只替代 IAM 的服务发现/JWKS；仍运行 Starter 的签名、声明和 Redis 撤销验证。
        app = new SpringApplicationBuilder(ProjectApplication.class).run(
                "--server.port=0", "--spring.profiles.active=local-file", "--spring.cloud.nacos.config.enabled=false", "--saas.forge.example.outbox.enabled=false", "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.discovery.client.simple.instances.iam-service[0].uri=http://127.0.0.1:" + jwks.getAddress().getPort(),
                "--security.jwt.issuer=example-test", "--saas.forge.environment=example-test",
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.producer.acks=all", "--management.tracing.sampling.probability=1.0",
                "--management.opentelemetry.tracing.export.otlp.endpoint=http://" + COLLECTOR.getHost()
                        + ":" + COLLECTOR.getMappedPort(4318) + "/v1/traces",
                "--spring.datasource.url=" + databaseUrl(),
                "--spring.datasource.username=project_app",
                "--spring.datasource.password=test-app",
                "--spring.datasource.hikari.maximum-pool-size=1");
        app.getBean(StringRedisTemplate.class).opsForValue().set("sf:example-test:iam-service:revocation-index-ready:v1:state", "1");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll static void stop() {
        if (app != null) app.close();
        if (jwks != null) jwks.stop(0);
    }

    /** 由诊断脚本提供本轮新构建的应用 JAR；不进入阶段 3 产品完成判定。 */
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "example.gateway.jar", matches = ".+")
    void gatewayExampleKafkaAuditAndCollectorDiagnostic() throws Exception {
        String originalBase = base;
        java.nio.file.Path output = java.nio.file.Path.of("../../.scratch/example-pipeline").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(output);
        Process gateway = null, audit = null;
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(connection, "CREATE ROLE audit_app LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT PASSWORD 'audit-fixture'");
            execute(connection, "CREATE DATABASE audit_fixture");
        }
        String auditUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/audit_fixture");
        try (var connection = DriverManager.getConnection(auditUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(connection, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            execute(connection, "GRANT USAGE ON SCHEMA public TO audit_app");
        }
        Flyway.configure().dataSource(auditUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../../saas-forge-services/audit-service/src/main/resources/db/migration").load().migrate();
        int gatewayPort = freePort(), auditPort = freePort();
        String otlp = "http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318) + "/v1/traces";
        try {
            gateway = launchFixture(System.getProperty("example.gateway.jar"), output.resolve("gateway.log"),
                    "--server.port=" + gatewayPort, "--spring.profiles.active=local-file",
                    "--saas.forge.gateway.configuration-revision=fixture", "--security.jwt.issuer=example-test",
                    "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                    "--spring.data.redis.password=", "--browser.rootDomain=saas.forge.test",
                    "--spring.cloud.discovery.client.simple.instances.project-service[0].uri=" + originalBase,
                    "--spring.cloud.discovery.client.simple.instances.iam-service[0].uri=http://127.0.0.1:" + jwks.getAddress().getPort(),
                    "--management.opentelemetry.tracing.export.otlp.endpoint=" + otlp);
            audit = launchFixture(System.getProperty("example.audit.jar"), output.resolve("audit.log"),
                    "--server.port=" + auditPort, "--spring.profiles.active=local-file",
                    "--saas.forge.audit.configuration-revision=fixture", "--saas.forge.audit.example-consumer.enabled=true", "--spring.datasource.url=" + auditUrl,
                    "--spring.datasource.username=audit_app", "--spring.datasource.password=audit-fixture",
                    "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                    "--management.endpoint.health.group.fixture.include=auditRuntimeReadiness",
                    "--management.opentelemetry.tracing.export.otlp.endpoint=" + otlp);
            base = "http://127.0.0.1:" + gatewayPort;
            awaitHttp(gateway, base + "/.well-known/jwks.json");
            awaitHttp(audit, "http://127.0.0.1:" + auditPort + "/actuator/health/fixture");
            try (var admin = org.apache.kafka.clients.admin.Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
                boolean assigned = false;
                while (!assigned && System.nanoTime() < deadline) {
                    try {
                        var group = admin.describeConsumerGroups(List.of("audit-service.example-events")).all().get(2, java.util.concurrent.TimeUnit.SECONDS);
                        assigned = group.values().stream().anyMatch(value -> value.members().stream().anyMatch(member -> !member.assignment().topicPartitions().isEmpty()));
                    } catch (Exception notReady) { Thread.sleep(100); }
                }
                assertThat(assigned).as("Audit consumer assigned before producing facts").isTrue();
            }
            var created = create(A, key(), "{\"name\":\"Gateway diagnostic\"}");
            assertThat(created.statusCode()).isEqualTo(201);
            String parent = created.headers().firstValue("Location").orElseThrow();
            String project = JSON.readTree(created.body()).path("id").asText();
            assertThat(update(A, parent, key(), "\"1\"", "{\"name\":\"Updated\"}").statusCode()).isEqualTo(200);
            var task = taskCreate(A, parent, key(), "{\"title\":\"Task\"}"); assertThat(task.statusCode()).isEqualTo(201);
            String child = task.headers().firstValue("Location").orElseThrow();
            assertThat(update(A, child, key(), "\"1\"", "{\"title\":\"Done\",\"status\":\"DONE\"}").statusCode()).isEqualTo(200);
            assertThat(deleteResource(A, child, key(), "\"2\"").statusCode()).isEqualTo(204);
            assertThat(deleteResource(A, parent, key(), "\"2\"").statusCode()).isEqualTo(204);
            var publisher = new ProjectOutboxPublisher(app.getBean(ProjectOutboxMapper.class), app.getBean(org.springframework.kafka.core.KafkaTemplate.class),
                    app.getBean(org.springframework.transaction.PlatformTransactionManager.class), app.getBean(io.opentelemetry.api.OpenTelemetry.class),
                    java.time.Duration.ofSeconds(30));
            for (int i = 0; i < 6; i++) publisher.publishNext();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            long records = 0;
            while (records < 6 && System.nanoTime() < deadline) {
                try (var connection = DriverManager.getConnection(auditUrl, "audit_app", "audit-fixture")) {
                    records = scalar(connection, "SELECT count(*) FROM audit_records WHERE metadata->>'projectId'='" + project + "'");
                }
                if (records < 6) Thread.sleep(100);
            }
            assertThat(records).isEqualTo(6);
            try (var connection = DriverManager.getConnection(auditUrl, "audit_app", "audit-fixture")) {
                assertThat(scalar(connection, "SELECT count(*) FROM audit_records WHERE trace_id IS NOT NULL")).isEqualTo(6);
            }
            app.getBean(io.opentelemetry.sdk.trace.SdkTracerProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (!(COLLECTOR.getLogs().contains("example.fact.consume") && COLLECTOR.getLogs().contains("Str(gateway)"))
                    && System.nanoTime() < deadline) Thread.sleep(100);
            java.nio.file.Files.writeString(output.resolve("collector.log"), COLLECTOR.getLogs());
            assertThat(COLLECTOR.getLogs()).contains("example.fact.consume", "example.fact.publish", "gateway", "project-service", "audit-service")
                    .doesNotContain("http.url", "never-export-secret@example.test");
            assertThat(java.nio.file.Files.readString(output.resolve("gateway.log"))).contains("http.request.completed");
            assertThat(java.nio.file.Files.readString(output.resolve("audit.log"))).contains("audit.record.appended");
        } finally {
            base = originalBase;
            for (Process process : new Process[]{gateway, audit}) if (process != null) {
                process.destroy();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static Process launchFixture(String jar, java.nio.file.Path log, String... arguments) throws Exception {
        assertThat(jar).isNotBlank(); assertThat(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(jar))).isTrue();
        var command = new ArrayList<String>(List.of(System.getProperty("java.home") + "/bin/java", "-Xmx256m", "-jar", jar,
                "--server.address=127.0.0.1", "--SAASFORGE_SECRETS_IMPORT=optional:configtree:/nonexistent-example-fixture/", "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.nacos.config.enabled=false", "--saas.forge.environment=example-test",
                "--management.tracing.sampling.probability=1.0", "--spring.main.banner-mode=off"));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitHttp(Process process, String url) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try { if (HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(1)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return; }
            catch (java.io.IOException notReady) { }
            Thread.sleep(100);
        }
        throw new AssertionError("Diagnostic process did not become ready; inspect its local log");
    }

    @Test @Order(-100) void sixCommittedFactsPublishWithTraceAndReplayProducesNoExtraFact() throws Exception {
        String writeKey = key();
        String sensitive = "never-export-secret@example.test";
        var created = create(A, writeKey, "{\"name\":\"" + sensitive + "\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String parent = created.headers().firstValue("Location").orElseThrow();
        String project = JSON.readTree(created.body()).path("id").asText();
        assertThat(create(A, writeKey, "{\"name\":\"" + sensitive + "\"}").body()).isEqualTo(created.body());
        assertThat(update(A, parent, key(), "\"1\"", "{\"name\":\"Updated\"}").statusCode()).isEqualTo(200);
        assertProblem(update(A, parent, key(), "\"1\"", "{\"name\":\"Stale\"}"), 409, "RESOURCE_VERSION_CONFLICT");
        var task = taskCreate(A, parent, key(), "{\"title\":\"Task\"}");
        assertThat(task.statusCode()).isEqualTo(201);
        String taskPath = task.headers().firstValue("Location").orElseThrow();
        assertThat(update(A, taskPath, key(), "\"1\"", "{\"title\":\"Done\",\"status\":\"DONE\"}").statusCode()).isEqualTo(200);
        assertThat(deleteResource(A, taskPath, key(), "\"2\"").statusCode()).isEqualTo(204);
        assertThat(deleteResource(A, parent, key(), "\"2\"").statusCode()).isEqualTo(204);
        assertProblem(request("GET", parent, token(B, false), null, null), 404, "PROJECT_NOT_FOUND");
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM project_outbox_events")).isEqualTo(6);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT event_snapshot::text, traceparent FROM project_outbox_events ORDER BY event_id")) {
                Set<String> types = new HashSet<>();
                while (rows.next()) {
                    String payload = rows.getString(1); assertThat(payload).doesNotContain(sensitive, "name", "title", "description");
                    var event = JSON.readTree(payload); types.add(event.path("type").asText());
                    assertThat(event.path("traceId").asText()).matches("[0-9a-f]{32}");
                    assertThat(rows.getString(2)).contains(event.path("traceId").asText());
                    assertThat(event.path("data").path("projectId").asText()).isEqualTo(project);
                }
                assertThat(types).hasSize(6);
            }
        }
        var publisher = new ProjectOutboxPublisher(app.getBean(ProjectOutboxMapper.class), app.getBean(org.springframework.kafka.core.KafkaTemplate.class),
                app.getBean(org.springframework.transaction.PlatformTransactionManager.class), app.getBean(io.opentelemetry.api.OpenTelemetry.class),
                java.time.Duration.ofSeconds(30));
        for (int i = 0; i < 6; i++) publisher.publishNext();
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM project_outbox_events WHERE published_at IS NOT NULL")).isEqualTo(6);
        }
        var config = new HashMap<String,Object>();
        config.put("bootstrap.servers", KAFKA.getBootstrapServers()); config.put("group.id", "example-proof-" + key());
        config.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put("auto.offset.reset", "earliest");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(config)) {
            consumer.subscribe(List.of("saas.forge.example-test.project-service.events"));
            var messages = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>>();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (messages.size() < 6 && System.nanoTime() < deadline)
                consumer.poll(java.time.Duration.ofMillis(250)).forEach(messages::add);
            assertThat(messages).hasSize(6);
            for (var message : messages) {
                assertThat(message.key()).isEqualTo(project);
                String propagated = new String(message.headers().lastHeader("traceparent").value(), StandardCharsets.US_ASCII);
                assertThat(propagated).contains(JSON.readTree(message.value()).path("traceId").asText());
            }
        }
        app.getBean(io.opentelemetry.sdk.trace.SdkTracerProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!COLLECTOR.getLogs().contains("example.fact.publish") && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(COLLECTOR.getLogs()).contains("example.fact.publish", "HTTP /api/v1/projects").doesNotContain(sensitive);
    }

    @Test @Order(-95) void failedPublishKeepsEventAndExpiredClaimCannotCompleteReplacement() throws Exception {
        var created = create(A, key(), "{\"name\":\"Retry proof\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        var mapper = app.getBean(ProjectOutboxMapper.class);
        var manager = app.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String,String> unavailable = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(unavailable.send(org.mockito.ArgumentMatchers.<org.apache.kafka.clients.producer.ProducerRecord<String,String>>any()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("never-export-kafka-secret")));
        new ProjectOutboxPublisher(mapper, unavailable, manager, app.getBean(io.opentelemetry.api.OpenTelemetry.class),
                java.time.Duration.ofSeconds(30)).publishNext();
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM project_outbox_events WHERE published_at IS NULL AND attempt_count=1 AND last_failure='ExecutionException'")).isEqualTo(1);
        }
        var future = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120);
        var first = transaction.execute(status -> mapper.claim(new ProjectOutboxMapper.Claim(key(), future, future.plusSeconds(30))));
        assertThat(first).isNotNull();
        assertThat((ProjectOutboxMapper.ClaimedEvent) transaction.execute(status -> mapper.claim(new ProjectOutboxMapper.Claim(key(), future.plusSeconds(1), future.plusSeconds(31))))).isNull();
        var second = transaction.execute(status -> mapper.claim(new ProjectOutboxMapper.Claim(key(), future.plusSeconds(31), future.plusSeconds(61))));
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.payload()).isEqualTo(first.payload());
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat((Integer) transaction.execute(status -> mapper.published(new ProjectOutboxMapper.Completion(first.eventId(), first.claimToken(), future.plusSeconds(32), null)))).isZero();
        assertThat((Integer) transaction.execute(status -> mapper.retry(new ProjectOutboxMapper.Completion(first.eventId(), first.claimToken(), future.plusSeconds(33), "stale")))).isZero();
        assertThat((Integer) transaction.execute(status -> mapper.published(new ProjectOutboxMapper.Completion(second.eventId(), second.claimToken(), future.plusSeconds(34), null)))).isEqualTo(1);
    }

    @Test @Order(-90) void outboxFailureRollsBackBusinessAndIdempotencyResult() throws Exception {
        long before;
        try (var connection = runtimeConnection()) { before = scalar(connection, "SELECT count(*) FROM project_outbox_events"); }
        try (var connection = migratorConnection()) {
            execute(connection, "CREATE FUNCTION fail_example_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'outbox unavailable'; END $$");
            execute(connection, "CREATE TRIGGER fail_example_outbox BEFORE INSERT ON project_outbox_events FOR EACH ROW EXECUTE FUNCTION fail_example_outbox()");
        }
        String writeKey = key();
        try { assertThat(create(A, writeKey, "{\"name\":\"Must rollback\"}").statusCode()).isEqualTo(503); }
        finally {
            try (var connection = migratorConnection()) {
                execute(connection, "DROP TRIGGER fail_example_outbox ON project_outbox_events");
                execute(connection, "DROP FUNCTION fail_example_outbox()");
            }
        }
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM project_outbox_events")).isEqualTo(before);
            connection.setAutoCommit(false); execute(connection, "SELECT set_config('app.tenant_id', '" + A + "', true)");
            assertThat(scalar(connection, "SELECT count(*) FROM projects WHERE name='Must rollback'")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM project_write_results WHERE idempotency_key='" + writeKey + "'")).isZero();
            connection.rollback();
        }
        assertThat(create(A, writeKey, "{\"name\":\"Must rollback\"}").statusCode()).isEqualTo(201);
    }

    @Test void tenantCanCreateAndReadProject() throws Exception {
        var created = create(A, key(), "{\"name\":\"First project\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode project = JSON.readTree(created.body());
        assertThat(project.path("name").asText()).isEqualTo("First project");
        assertThat(project.path("version").asLong()).isEqualTo(1);
        assertThat(project.path("description").isNull()).isTrue();
        assertThat(UUID.fromString(project.path("id").asText()).version()).isEqualTo(7);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/api/v1/projects/" + project.path("id").asText());
        var read = request("GET", location, token(A, false), null, null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(read.body())).isEqualTo(project);
    }

    @Test void retryReplaysOriginalCreationButChangedBodyOrTenantCannotReuseKey() throws Exception {
        String key = key();
        var first = create(A, key, "{\"name\":\"Repeat\"}");
        var replay = create(A, key, "{\"description\":null,\"name\":\"Repeat\"}");
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        var changed = create(A, key, "{\"name\":\"Changed\"}");
        assertProblem(changed, 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(create(B, key, "{\"name\":\"Repeat\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        var own = create(B, key(), "{\"name\":\"Repeat\"}");
        assertThat(own.statusCode()).isEqualTo(201);
        assertThat(own.body()).isNotEqualTo(first.body());
    }

    @Test void validationDoesNotReserveKeyAndRejectsWritableSystemFields() throws Exception {
        String key = key();
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\"   \"}",
                "{\"name\":42}", "{\"name\":\"" + "x".repeat(201) + "\"}",
                "{\"name\":\"ok\",\"description\":\"" + "x".repeat(2001) + "\"}",
                "{\"name\":\"ok\",\"id\":\"" + key() + "\"}",
                "{\"name\":\"ok\",\"version\":2}", "{\"name\":\"ok\",\"createdAt\":null}",
                "{\"name\":\"ok\",\"updatedAt\":null}", "{\"name\":\"A\\u0000B\"}", "{\"name\":\"ok\",\"description\":\"\\u0000\"}", "{invalid")) {
            var response = create(A, key, body);
            assertProblem(response, 400, "VALIDATION_FAILED");
            assertThat(JSON.readTree(response.body()).path("errors").isArray()).as(response.body()).isTrue();
            assertThat(JSON.readTree(response.body()).has("instance")).isFalse();
        }
        assertThat(create(A, key, "{\"name\":\"" + "😀".repeat(200) + "\"}").statusCode()).isEqualTo(201);
        assertProblem(create(A, null, "{\"name\":\"ok\"}"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        for (String invalid : List.of("bad", UUID.randomUUID().toString(), key().toUpperCase(Locale.ROOT)))
            assertProblem(create(A, invalid, "{\"name\":\"ok\"}"), 400, "IDEMPOTENCY_KEY_INVALID");
    }

    @Test void tenantsAreIsolatedAndUntrustedCallersCannotReadOrWrite() throws Exception {
        var a = create(A, key(), "{\"name\":\"Same name\",\"description\":\"Details\"}");
        var a2 = create(A, key(), "{\"name\":\"Same name\"}");
        var b = create(B, key(), "{\"name\":\"Same name\"}");
        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(a2.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        String pathA = a.headers().firstValue("Location").orElseThrow();
        String pathB = b.headers().firstValue("Location").orElseThrow();
        assertThat(request("GET", pathB, token(B, false), null, null).statusCode()).isEqualTo(200);
        assertProblem(request("GET", pathA, token(B, false), null, null), 404, "PROJECT_NOT_FOUND");
        assertProblem(request("GET", pathB, token(A, false), null, null), 404, "PROJECT_NOT_FOUND");
        assertProblem(request("GET", "/api/v1/projects/" + key(), token(A, false), null, null), 404, "PROJECT_NOT_FOUND");
        for (String credential : Arrays.asList(null, "Bearer invalid", token(null, true))) {
            assertProblem(request("GET", pathA, credential, null, null), 401, "ACCESS_TOKEN_INVALID");
            assertProblem(request("POST", "/api/v1/projects", credential, key(), "{\"name\":\"Denied\"}"), 401, "ACCESS_TOKEN_INVALID");
        }
        for (String method : List.of("GET", "POST"))
            assertProblem(request(method, method.equals("GET") ? pathA : "/api/v1/projects", token(null, false), key(),
                    method.equals("GET") ? null : "{\"name\":\"Denied\"}"), 403, "ACCESS_CONTEXT_UNAVAILABLE");
        for (String field : List.of("tenantId", "tenant_id", "TenantId")) {
            assertThat(create(A, key(), "{\"name\":\"Denied\",\"" + field + "\":\"" + B + "\"}").statusCode()).isEqualTo(400);
            assertThat(request("GET", pathA + "?" + field + "=" + B, token(A, false), null, null).statusCode()).isEqualTo(400);
        }
        var forged = HTTP.send(HttpRequest.newBuilder(URI.create(base + pathA)).header("Authorization", token(A, false))
                .header("X-Tenant-Id", B).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forged.statusCode()).isEqualTo(400);
        for (String header : List.of("X-Tenant-Context", "X-Tenant-Id")) {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(base + pathA)).header("Authorization", token(A, false))
                    .header(header, B).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).path("errors").isArray()).isTrue();
        }
    }

    @Test void signaturesRevocationAndCanonicalResourceIdsAreEnforced() throws Exception {
        var created = create(A, key(), "{\"name\":\"Verified\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        var original = SignedJWT.parse(token(A, false).substring(7));
        var signed = new SignedJWT(original.getHeader(), original.getJWTClaimsSet());
        signed.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
        assertProblem(request("GET", path, "Bearer " + signed.serialize(), null, null), 401, "ACCESS_TOKEN_INVALID");
        var redis = app.getBean(StringRedisTemplate.class);
        String ready = "sf:example-test:iam-service:revocation-index-ready:v1:state";
        redis.delete(ready);
        try { assertProblem(request("GET", path, token(A, false), null, null), 503, "TOKEN_REVOCATION_STATUS_UNAVAILABLE"); }
        finally { redis.opsForValue().set(ready, "1"); }
        assertThat(request("GET", path, token(A, false), null, null).statusCode()).isEqualTo(200);
        for (String invalid : List.of("bad", UUID.randomUUID().toString(), key().toUpperCase(Locale.ROOT)))
            assertProblem(request("GET", "/api/v1/projects/" + invalid, token(A, false), null, null), 400, "VALIDATION_FAILED");
        JsonNode resource = JSON.readTree(created.body());
        assertThat(resource.propertyNames()).containsExactlyInAnyOrder("id", "name", "description", "version", "createdAt", "updatedAt");
        for (String timestamp : List.of("createdAt", "updatedAt"))
            assertThat(resource.path(timestamp).asText()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
    }

    @Test void unsupportedMediaTypesAndUnacceptableRepresentationsHaveContractErrors() throws Exception {
        var unsupported = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/projects"))
                .header("Authorization", token(A, false)).header("Idempotency-Key", key())
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("name=wrong"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(unsupported, 415, "UNSUPPORTED_MEDIA_TYPE");
        var unacceptable = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/projects/" + key()))
                .header("Authorization", token(A, false)).header("Accept", "application/xml").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertProblem(unacceptable, 406, "NOT_ACCEPTABLE");
    }

    @Test void runtimeRoleCannotBypassRlsOrWriteAcrossTenants() throws Exception {
        create(A, key(), "{\"name\":\"RLS A\"}");
        create(B, key(), "{\"name\":\"RLS B\"}");
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM project_write_results")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls OR rolinherit)")).isZero();
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection, "SET ROLE project_migrator"));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection, "ALTER TABLE projects DISABLE ROW LEVEL SECURITY"));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection, "TRUNCATE projects"));
            assertThat(scalar(connection, "SELECT count(*) FROM pg_class WHERE relname IN ('projects','project_write_results') AND relrowsecurity AND relforcerowsecurity AND pg_get_userbyid(relowner)='project_migrator'")).isEqualTo(2);
            for (String context : List.of("", "invalid", A)) {
                connection.setAutoCommit(false);
                try {
                    setTenant(connection, context);
                    if (context.equals("invalid")) {
                        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> scalar(connection, "SELECT count(*) FROM projects"));
                        connection.rollback();
                        setTenant(connection, context);
                    } else if (context.isEmpty()) assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
                    else assertThat(scalar(connection, "SELECT count(*) FROM projects WHERE tenant_id='" + B + "'")).isZero();
                    org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                            "INSERT INTO projects(tenant_id,name) VALUES ('" + B + "','Forbidden')"));
                } finally { connection.rollback(); connection.setAutoCommit(true); }
            }
            for (String context : List.of("", "invalid", A)) {
                connection.setAutoCommit(false);
                try {
                    setTenant(connection, context);
                    org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                            "INSERT INTO project_write_results(identity_id,idempotency_key,tenant_id,fingerprint) VALUES ('"
                                    + ID + "','" + key() + "','" + B + "','forbidden')"));
                } finally { connection.rollback(); connection.setAutoCommit(true); }
            }
            connection.setAutoCommit(false);
            setTenant(connection, A);
            assertThat(scalar(connection, "SELECT count(DISTINCT tenant_id) FROM projects")).isEqualTo(1);
            assertThat(scalar(connection, "SELECT count(*) FROM project_write_results WHERE tenant_id='" + B + "'")).isZero();
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE project_write_results SET tenant_id='" + B + "'"));
            connection.rollback();
        }
    }

    @Test void samePooledConnectionClearsContextAfterCommitAndInfrastructureRollback() throws Exception {
        var pool = app.getBean(javax.sql.DataSource.class);
        long pid;
        try (var connection = pool.getConnection()) { pid = scalar(connection, "SELECT pg_backend_pid()"); }
        var a = create(A, key(), "{\"name\":\"Commit\"}");
        assertThat(a.statusCode()).isEqualTo(201);
        try (var connection = pool.getConnection()) {
            assertThat(scalar(connection, "SELECT pg_backend_pid()")).isEqualTo(pid);
            assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
        }
        String retryKey = key();
        try (var migration = migratorConnection()) {
            execute(migration, "CREATE FUNCTION reject_project_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test infrastructure outage'; END $$");
            execute(migration, "CREATE TRIGGER reject_project_test BEFORE INSERT ON projects FOR EACH ROW EXECUTE FUNCTION reject_project_test()");
            try {
                assertProblem(create(A, retryKey, "{\"name\":\"Rollback\"}"), 503, "INFRASTRUCTURE_UNAVAILABLE");
            } finally {
                execute(migration, "DROP TRIGGER reject_project_test ON projects");
                execute(migration, "DROP FUNCTION reject_project_test()");
            }
        }
        try (var connection = pool.getConnection()) {
            assertThat(scalar(connection, "SELECT pg_backend_pid()")).isEqualTo(pid);
            assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM project_write_results")).isZero();
        }
        // 基础设施失败必须释放键；切换 Tenant 后可用相同键首次成功，并且不能读取 A。
        var b = create(B, retryKey, "{\"name\":\"Rollback\"}");
        assertThat(b.statusCode()).isEqualTo(201);
        assertProblem(request("GET", a.headers().firstValue("Location").orElseThrow(), token(B, false), null, null), 404, "PROJECT_NOT_FOUND");
        try (var connection = pool.getConnection()) {
            assertThat(scalar(connection, "SELECT pg_backend_pid()")).isEqualTo(pid);
            assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
        }
    }

    @Test void completedResultsExpireAfterTwentyFourHoursAndReplayStoredBusinessFailure() throws Exception {
        String key = key();
        var first = create(A, key, "{\"name\":\"Retained\"}");
        assertThat(first.statusCode()).isEqualTo(201);
        try (var migration = migratorConnection()) {
            execute(migration, "UPDATE project_write_results SET completed_at=clock_timestamp()-interval '23 hours' WHERE idempotency_key='" + key + "'");
            assertThat(create(A, key, "{\"name\":\"Retained\"}").body()).isEqualTo(first.body());
            execute(migration, "UPDATE project_write_results SET completed_at=clock_timestamp()-interval '25 hours' WHERE idempotency_key='" + key + "'");
        }
        var next = create(A, key, "{\"name\":\"Retained\"}");
        assertThat(next.statusCode()).isEqualTo(201);
        assertThat(next.body()).isNotEqualTo(first.body());
        // 本切片创建没有自然业务 4xx；受控完成记录只证明既存稳定失败的重放协议，不冒充业务场景验收。
        String stableFailure = "{\"type\":\"urn:saas.forge:problem:fixture-business-rejected\",\"title\":\"Conflict\",\"status\":409,\"code\":\"FIXTURE_BUSINESS_REJECTED\",\"detail\":\"Fixture rejection.\",\"traceId\":\"123456789012345678901234567890abcf\"}";
        try (var migration = migratorConnection(); var statement = migration.prepareStatement(
                "UPDATE project_write_results SET status=409,body=?,location=NULL WHERE idempotency_key=?")) {
            statement.setString(1, stableFailure); statement.setObject(2, UUID.fromString(key)); statement.executeUpdate();
        }
        var failed = create(A, key, "{\"name\":\"Retained\"}");
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(failed.body()).isEqualTo(stableFailure);
        assertThat(failed.headers().firstValue("Location")).isEmpty();
        assertProblem(create(B, key, "{\"name\":\"Retained\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        try (var migration = migratorConnection()) {
            execute(migration, "UPDATE project_write_results SET completed_at=clock_timestamp()-interval '25 hours' WHERE idempotency_key='" + key + "'");
            assertProblem(create(B, key, "{\"name\":\"Retained\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
            execute(migration, java.nio.file.Files.readString(java.nio.file.Path.of("deploy/expire-write-results.sql")));
        }
        assertThat(create(B, key, "{\"name\":\"Retained\"}").statusCode()).isEqualTo(201);
    }

    @Test @Order(Integer.MAX_VALUE) void concurrentRequestIsRejectedWhileTheFirstTransactionIsInProgress() throws Exception {
        var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(2);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        String key = key();
        try (var migration = migratorConnection()) {
            migration.setAutoCommit(false);
            execute(migration, "LOCK TABLE projects IN ACCESS EXCLUSIVE MODE");
            var first = worker.submit(() -> create(A, key, "{\"name\":\"Concurrent\"}"));
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                boolean waiting = false;
                while (System.nanoTime() < deadline && !waiting) {
                    try (var observer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                        waiting = scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE usename='project_app' AND wait_event_type='Lock'") > 0;
                    }
                    if (!waiting) Thread.sleep(20);
                }
                assertThat(waiting).as("first HTTP transaction reached the held database lock").isTrue();
                var duplicate = create(A, key, "{\"name\":\"Concurrent\"}");
                assertProblem(duplicate, 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
                assertThat(duplicate.headers().firstValue("Retry-After")).contains("1");
            } finally { migration.rollback(); }
            var completed = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(completed.statusCode()).isEqualTo(201);
            assertThat(create(A, key, "{\"name\":\"Concurrent\"}").body()).isEqualTo(completed.body());
        } finally { worker.shutdownNow(); pool.setMaximumPoolSize(1); }
    }

    @Test void projectPagesAreStableTenantScopedAndValidateCursors() throws Exception {
        String tenant = key();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            var created = create(tenant, key(), "{\"name\":\"Duplicate\"}");
            assertThat(created.statusCode()).isEqualTo(201);
            ids.add(JSON.readTree(created.body()).path("id").asText());
        }
        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            var response = request("GET", "/api/v1/projects?limit=2" + (cursor == null ? "" : "&cursor=" + cursor),
                    token(tenant, false), null, null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var page = JSON.readTree(response.body());
            page.path("items").forEach(item -> seen.add(item.path("id").asText()));
            if (cursor == null) {
                String next = page.path("nextCursor").asText();
                assertProblem(request("GET", "/api/v1/projects?limit=2&cursor=" + next, token(B, false), null, null), 400, "VALIDATION_FAILED");
                assertProblem(request("GET", "/api/v1/projects?limit=3&cursor=" + next, token(tenant, false), null, null), 400, "VALIDATION_FAILED");
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            assertThat(page.path("hasMore").asBoolean()).isEqualTo(cursor != null);
        } while (cursor != null);
        ids.sort(String::compareTo);
        assertThat(seen).containsExactlyElementsOf(ids);
        for (String query : List.of("limit=0", "limit=101", "limit=x", "limit=", "limit=1&limit=2", "cursor=bad", "cursor=", "tenantId=" + B))
            assertProblem(request("GET", "/api/v1/projects?" + query, token(tenant, false), null, null), 400, "VALIDATION_FAILED");
    }

    @Test void updateReplaysItsOriginalResultAndRecoversAfterVersionConflict() throws Exception {
        var created = create(A, key(), "{\"name\":\"Before\",\"description\":\"old\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        assertThat(create(A, key(), "{\"name\":\"After\"}").statusCode()).isEqualTo(201);
        String writeKey = key();
        var first = update(A, path, writeKey, "\"1\"", "{\"name\":\"After\"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var project = JSON.readTree(first.body());
        assertThat(project.path("name").asText()).isEqualTo("After");
        assertThat(project.path("description").isNull()).isTrue();
        assertThat(project.path("version").asLong()).isEqualTo(2);
        assertThat(project.path("id")).isEqualTo(JSON.readTree(created.body()).path("id"));
        assertThat(project.path("createdAt")).isEqualTo(JSON.readTree(created.body()).path("createdAt"));
        assertThat(project.path("updatedAt").asText()).isGreaterThanOrEqualTo(project.path("createdAt").asText());
        assertThat(update(A, path, writeKey, "\"1\"", "{\"description\":null,\"name\":\"After\"}").body()).isEqualTo(first.body());
        assertProblem(update(A, path, writeKey, "\"2\"", "{\"name\":\"After\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        String staleKey = key();
        var stale = update(A, path, staleKey, "\"1\"", "{\"name\":\"Lost\"}");
        assertProblem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertThat(update(A, path, staleKey, "\"1\"", "{\"name\":\"Lost\"}").body()).isEqualTo(stale.body());
        assertThat(request("GET", path, token(A, false), null, null).body()).isEqualTo(first.body());
        var recovered = update(A, path, key(), "\"2\"", "{\"name\":\"Recovered\",\"description\":\"new\"}");
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(recovered.body()).path("version").asLong()).isEqualTo(3);
        // 后续版本变化后，旧成功与旧冲突仍重放当时的稳定结果。
        assertThat(update(A, path, writeKey, "\"1\"", "{\"name\":\"After\"}").body()).isEqualTo(first.body());
        assertThat(update(A, path, staleKey, "\"1\"", "{\"name\":\"Lost\"}").body()).isEqualTo(stale.body());
    }

    @Test void defaultAndMaximumPagesHaveCorrectTerminalAndEmptyResults() throws Exception {
        String tenant = key();
        var empty = JSON.readTree(request("GET", "/api/v1/projects", token(tenant, false), null, null).body());
        assertThat(empty.path("items").size()).isZero();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
        for (int i = 0; i < 101; i++) assertThat(create(tenant, key(), "{\"name\":\"Pages\"}").statusCode()).isEqualTo(201);
        var first = JSON.readTree(request("GET", "/api/v1/projects", token(tenant, false), null, null).body());
        assertThat(first.path("items").size()).isEqualTo(50);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var maximum = JSON.readTree(request("GET", "/api/v1/projects?limit=100", token(tenant, false), null, null).body());
        assertThat(maximum.path("items").size()).isEqualTo(100);
        String cursor = maximum.path("nextCursor").asText();
        var last = JSON.readTree(request("GET", "/api/v1/projects?limit=100&cursor=" + cursor, token(tenant, false), null, null).body());
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("hasMore").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
        var exact = JSON.readTree(request("GET", "/api/v1/projects?limit=1&cursor=" +
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("projects-v1:" + tenant + ":1:" +
                        maximum.path("items").get(99).path("id").asText()).getBytes(StandardCharsets.UTF_8)), token(tenant, false), null, null).body());
        assertThat(exact.path("items").size()).isEqualTo(1);
        assertThat(exact.path("hasMore").asBoolean()).isFalse();
        for (String value : List.of("tasks-v1:" + tenant + ":100:" + key(), "projects-v1:" + tenant + ":100:" + key(),
                "projects-v1:" + tenant + ":100:invalid")) {
            String bad = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            assertProblem(request("GET", "/api/v1/projects?limit=100&cursor=" + bad, token(tenant, false), null, null), 400, "VALIDATION_FAILED");
        }
    }

    @Test void updateValidationDoesNotReserveKeysAndCannotWriteSystemFields() throws Exception {
        var created = create(A, key(), "{\"name\":\"Untouched\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        String retryKey = key();
        assertProblem(update(A, path, retryKey, null, "{\"name\":\"Changed\"}"), 428, "VERSION_REQUIRED");
        for (String version : List.of("", "1", "\"0\"", "\"01\"", "*", "W/\"1\"", "\"1\",\"2\"", "\"9223372036854775808\""))
            assertProblem(update(A, path, retryKey, version, "{\"name\":\"Changed\"}"), 400, "VALIDATION_FAILED");
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\" \"}", "{\"name\":42}",
                "{\"name\":\"" + "x".repeat(201) + "\"}", "{\"name\":\"N\",\"description\":\"" + "x".repeat(2001) + "\"}",
                "{\"name\":\"N\u0000\"}"))
            assertProblem(update(A, path, retryKey, "\"1\"", body), 400, "VALIDATION_FAILED");
        for (String field : List.of("id", "version", "createdAt", "updatedAt", "tenantId", "tenant_id", "TenantId"))
            assertProblem(update(A, path, retryKey, "\"1\"", "{\"name\":\"Changed\",\"" + field + "\":null}"), 400, "VALIDATION_FAILED");
        assertThat(request("GET", path, token(A, false), null, null).body()).isEqualTo(created.body());
        assertThat(update(A, path, retryKey, "\"1\"", "{\"name\":\"" + "😀".repeat(200) + "\"}").statusCode()).isEqualTo(200);
    }

    @Test void tenantSwitchCannotListReferenceUpdateOrReplayForeignProjects() throws Exception {
        String tenant = key();
        String createKey = key();
        var created = create(tenant, createKey, "{\"name\":\"Private\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        assertProblem(update(tenant, path, createKey, "\"1\"", "{\"name\":\"Private\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        String updateKey = key();
        var own = update(tenant, path, updateKey, "\"1\"", "{\"name\":\"Private\"}");
        assertThat(own.statusCode()).isEqualTo(200);
        assertProblem(create(tenant, updateKey, "{\"name\":\"Private\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(update(B, path, updateKey, "\"1\"", "{\"name\":\"Private\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        String foreignKey = key();
        var denied = update(B, path, foreignKey, "\"2\"", "{\"name\":\"Intrusion\"}");
        assertProblem(denied, 404, "PROJECT_NOT_FOUND");
        assertThat(update(B, path, foreignKey, "\"2\"", "{\"name\":\"Intrusion\"}").body()).isEqualTo(denied.body());
        assertProblem(update(B, "/api/v1/projects/" + key(), key(), "\"1\"", "{\"name\":\"Intrusion\"}"), 404, "PROJECT_NOT_FOUND");
        assertProblem(request("GET", path, token(B, false), null, null), 404, "PROJECT_NOT_FOUND");
        var foreignPage = request("GET", "/api/v1/projects?limit=100", token(B, false), null, null);
        assertThat(foreignPage.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(foreignPage.body()).path("items")).noneSatisfy(item -> assertThat(item.path("id")).isEqualTo(JSON.readTree(created.body()).path("id")));
        assertThat(request("GET", path, token(tenant, false), null, null).body()).isEqualTo(own.body());
        for (String credential : Arrays.asList(null, "Bearer invalid", token(null, true))) {
            assertProblem(request("GET", "/api/v1/projects", credential, null, null), 401, "ACCESS_TOKEN_INVALID");
            assertProblem(request("PUT", path, credential, key(), "{\"name\":\"Denied\"}"), 401, "ACCESS_TOKEN_INVALID");
        }
        for (String method : List.of("GET", "PUT"))
            assertProblem(request(method, method.equals("GET") ? "/api/v1/projects" : path, token(null, false), key(),
                    method.equals("GET") ? null : "{\"name\":\"Denied\"}"), 403, "ACCESS_CONTEXT_UNAVAILABLE");
    }

    @Test void versionedUpdateInfrastructureFailureRollsBackAndReleasesTheKey() throws Exception {
        var created = create(A, key(), "{\"name\":\"Rollback update\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        String writeKey = key();
        try (var migration = migratorConnection()) {
            execute(migration, "CREATE FUNCTION reject_update_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test update outage'; END $$");
            execute(migration, "CREATE TRIGGER reject_update_test BEFORE UPDATE ON projects FOR EACH ROW EXECUTE FUNCTION reject_update_test()");
            try { assertProblem(update(A, path, writeKey, "\"1\"", "{\"name\":\"Retry\"}"), 503, "INFRASTRUCTURE_UNAVAILABLE"); }
            finally {
                execute(migration, "DROP TRIGGER reject_update_test ON projects");
                execute(migration, "DROP FUNCTION reject_update_test()");
            }
        }
        assertThat(request("GET", path, token(A, false), null, null).body()).isEqualTo(created.body());
        try (var connection = app.getBean(javax.sql.DataSource.class).getConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM project_write_results")).isZero();
        }
        assertThat(update(A, path, writeKey, "\"1\"", "{\"name\":\"Retry\"}").statusCode()).isEqualTo(200);
    }

    @Test void databaseUpdatePrivilegesPreserveRlsAndImmutableOwnership() throws Exception {
        var a = create(A, key(), "{\"name\":\"DB A\"}");
        var b = create(B, key(), "{\"name\":\"DB B\"}");
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM information_schema.column_privileges WHERE grantee='project_app' AND table_name='projects' AND privilege_type='UPDATE' AND column_name IN ('tenant_id','id','created_at')")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM information_schema.column_privileges WHERE grantee='project_app' AND table_name='projects' AND privilege_type='UPDATE'")).isEqualTo(4);
            for (String context : List.of("", "invalid", A)) {
                connection.setAutoCommit(false);
                try {
                    setTenant(connection, context);
                    try (var statement = connection.createStatement()) {
                        if (context.equals("invalid")) org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                                () -> statement.executeUpdate("UPDATE projects SET name='Forbidden'"));
                        else if (context.isEmpty()) assertThat(statement.executeUpdate("UPDATE projects SET name='Forbidden'")).isZero();
                        else {
                            assertThat(statement.executeUpdate("UPDATE projects SET name='Forbidden' WHERE tenant_id='" + B + "'")).isZero();
                            assertThat(statement.executeUpdate("UPDATE projects SET name='Owned'")).isGreaterThan(0);
                        }
                    }
                } finally { connection.rollback(); connection.setAutoCommit(true); }
            }
        }
        assertThat(request("GET", a.headers().firstValue("Location").orElseThrow(), token(A, false), null, null).body()).isEqualTo(a.body());
        assertThat(request("GET", b.headers().firstValue("Location").orElseThrow(), token(B, false), null, null).body()).isEqualTo(b.body());
    }

    @Test @Order(Integer.MAX_VALUE - 1) void twoIdentitiesCompetingForOneVersionHaveExactlyOneWinner() throws Exception {
        var created = create(A, key(), "{\"name\":\"Concurrent update\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        String id = JSON.readTree(created.body()).path("id").asText();
        var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        String firstKey = key();
        String secondKey = key();
        try (var migration = migratorConnection()) {
            migration.setAutoCommit(false);
            execute(migration, "SELECT id FROM projects WHERE id='" + id + "' FOR UPDATE");
            var first = workers.submit(() -> update(A, path, firstKey, "\"1\"", "{\"name\":\"Winner A\"}"));
            String otherIdentity = key();
            var second = workers.submit(() -> HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", token(A, false, otherIdentity)).header("Idempotency-Key", secondKey)
                    .header("If-Match", "\"1\"").header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Winner B\"}")).build(), HttpResponse.BodyHandlers.ofString()));
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                boolean bothWaiting = false;
                while (System.nanoTime() < deadline && !bothWaiting) {
                    try (var observer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                        bothWaiting = scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE usename='project_app' AND wait_event_type='Lock'") == 2;
                    }
                    if (!bothWaiting) Thread.sleep(20);
                }
                assertThat(bothWaiting).as("both HTTP writes reached the locked resource with independent connections").isTrue();
            } finally { migration.rollback(); }
            var responses = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            var winner = responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow();
            var loser = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertProblem(loser, 409, "RESOURCE_VERSION_CONFLICT");
            var read = request("GET", path, token(A, false), null, null);
            assertThat(read.body()).isEqualTo(winner.body());
            assertThat(JSON.readTree(read.body()).path("version").asLong()).isEqualTo(2);
            assertThat(update(A, path, key(), "\"2\"", "{\"name\":\"Recovered\"}").statusCode()).isEqualTo(200);
        } finally { workers.shutdownNow(); pool.setMaximumPoolSize(1); }
    }

    static HttpResponse<String> update(String tenant, String path, String key, String version, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", token(tenant, false))
                .header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        if (version != null) builder.header("If-Match", version);
        return HTTP.send(builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void tenantCanCreateAndReadTaskWithFixedInitialStateAndReplay() throws Exception {
        String parent = create(A, key(), "{\"name\":\"Tasks\"}").headers().firstValue("Location").orElseThrow();
        String collection = parent + "/tasks";
        String writeKey = key();
        var created = request("POST", collection, token(A, false), writeKey, "{\"title\":\"First task\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var task = JSON.readTree(created.body());
        assertThat(task.path("title").asText()).isEqualTo("First task");
        assertThat(task.path("status").asText()).isEqualTo("TODO");
        assertThat(task.path("version").asLong()).isEqualTo(1);
        assertThat(task.path("projectId").asText()).isEqualTo(parent.substring(parent.lastIndexOf('/') + 1));
        assertThat(task.path("description").isNull()).isTrue();
        assertThat(UUID.fromString(task.path("id").asText()).version()).isEqualTo(7);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo(collection + "/" + task.path("id").asText());
        assertThat(JSON.readTree(request("GET", location, token(A, false), null, null).body())).isEqualTo(task);
        var replay = request("POST", collection, token(A, false), writeKey, "{\"description\":null,\"title\":\"First task\"}");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(replay.headers().firstValue("Location")).isEqualTo(created.headers().firstValue("Location"));
        var list = request("GET", collection, token(A, false), null, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(list.body()).path("items")).hasSize(1);
    }

    @Test void taskParentsAndTenantIdentityCannotBeForgedAndFailuresReplay() throws Exception {
        String a = projectPath(A), otherA = projectPath(A), b = projectPath(B);
        var taskA = taskCreate(A, a, key(), "{\"title\":\"Same\"}");
        var taskB = taskCreate(B, b, key(), "{\"title\":\"Same\"}");
        assertThat(taskA.statusCode()).isEqualTo(201);
        assertThat(taskB.statusCode()).isEqualTo(201);
        String locationA = taskA.headers().firstValue("Location").orElseThrow();
        String locationB = taskB.headers().firstValue("Location").orElseThrow();
        assertThat(request("GET", locationB, token(B, false), null, null).statusCode()).isEqualTo(200);
        assertProblem(request("GET", locationA, token(B, false), null, null), 404, "PROJECT_NOT_FOUND");
        assertProblem(request("GET", locationB, token(A, false), null, null), 404, "PROJECT_NOT_FOUND");
        assertProblem(request("GET", locationA.replace(a, otherA), token(A, false), null, null), 404, "TASK_NOT_FOUND");
        assertProblem(request("GET", a + "/tasks/" + key(), token(A, false), null, null), 404, "TASK_NOT_FOUND");
        assertProblem(request("GET", b + "/tasks", token(A, false), null, null), 404, "PROJECT_NOT_FOUND");
        for (String parent : List.of(b, "/api/v1/projects/" + key())) {
            String writeKey = key();
            var failed = taskCreate(A, parent, writeKey, "{\"title\":\"Forbidden\"}");
            assertProblem(failed, 404, "PROJECT_NOT_FOUND");
            var replay = taskCreate(A, parent, writeKey, "{\"title\":\"Forbidden\"}");
            assertProblem(replay, 404, "PROJECT_NOT_FOUND");
            assertThat(replay.body()).isEqualTo(failed.body());
            assertThat(replay.headers().firstValue("Location")).isEmpty();
            assertProblem(taskCreate(A, a, writeKey, "{\"title\":\"Forbidden\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        }
        assertThat(JSON.readTree(request("GET", b + "/tasks", token(B, false), null, null).body()).path("items")).hasSize(1);
        for (String path : List.of(a + "/tasks", locationA)) {
            for (String credential : Arrays.asList(null, "Bearer invalid", token(null, true)))
                assertProblem(request("GET", path, credential, null, null), 401, "ACCESS_TOKEN_INVALID");
            assertProblem(request("GET", path, token(null, false), null, null), 403, "ACCESS_CONTEXT_UNAVAILABLE");
        }
        for (String credential : Arrays.asList(null, "Bearer invalid", token(null, true)))
            assertProblem(request("POST", a + "/tasks", credential, key(), "{\"title\":\"Denied\"}"), 401, "ACCESS_TOKEN_INVALID");
        assertProblem(request("POST", a + "/tasks", token(null, false), key(), "{\"title\":\"Denied\"}"), 403, "ACCESS_CONTEXT_UNAVAILABLE");
        for (String field : List.of("tenantId", "tenant_id", "TenantId")) {
            assertProblem(taskCreate(A, a, key(), "{\"title\":\"Denied\",\"" + field + "\":\"" + B + "\"}"), 400, "VALIDATION_FAILED");
            assertProblem(request("GET", a + "/tasks?" + field + "=" + B, token(A, false), null, null), 400, "VALIDATION_FAILED");
        }
        for (String method : List.of("GET", "POST")) {
            var builder = HttpRequest.newBuilder(URI.create(base + a + "/tasks"))
                    .header("Authorization", token(A, false)).header("X-Tenant-Id", B);
            if (method.equals("POST")) builder.header("Content-Type", "application/json").header("Idempotency-Key", key());
            var forged = HTTP.send(builder.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{\"title\":\"Denied\"}")
                    : HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(forged.statusCode()).isEqualTo(400);
        }
        String projectKey = key();
        assertThat(create(A, projectKey, "{\"name\":\"Shared key\"}").statusCode()).isEqualTo(201);
        assertProblem(taskCreate(A, a, projectKey, "{\"title\":\"Shared key\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        String taskKey = key();
        assertThat(taskCreate(A, a, taskKey, "{\"title\":\"Shared key\"}").statusCode()).isEqualTo(201);
        assertProblem(create(A, taskKey, "{\"name\":\"Shared key\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(taskCreate(A, otherA, taskKey, "{\"title\":\"Shared key\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(taskCreate(B, b, taskKey, "{\"title\":\"Shared key\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test void taskFieldsAndSystemInputsFollowContractWithoutReservingInvalidKeys() throws Exception {
        String parent = projectPath(A), writeKey = key();
        for (String body : List.of("{}", "{\"title\":null}", "{\"title\":\"  \"}", "{\"title\":1}",
                "{\"title\":true}", "{\"title\":\"" + "x".repeat(201) + "\"}",
                "{\"title\":\"ok\",\"description\":\"" + "x".repeat(2001) + "\"}",
                "{\"title\":\"A\\u0000B\"}", "{\"title\":\"ok\",\"description\":\"\\u0000\"}",
                "{invalid", "{\"title\":\"ok\",\"status\":\"IN_PROGRESS\"}",
                "{\"title\":\"ok\",\"status\":\"TODO\"}", "{\"title\":\"ok\",\"projectId\":\"" + key() + "\"}",
                "{\"title\":\"ok\",\"id\":\"" + key() + "\"}", "{\"title\":\"ok\",\"version\":2}",
                "{\"title\":\"ok\",\"createdAt\":null}", "{\"title\":\"ok\",\"updatedAt\":null}")) {
            var invalid = taskCreate(A, parent, writeKey, body);
            assertProblem(invalid, 400, "VALIDATION_FAILED");
            assertThat(JSON.readTree(invalid.body()).path("errors").isArray()).isTrue();
        }
        String title = "😀".repeat(200), description = "😀".repeat(2000);
        var created = taskCreate(A, parent, writeKey, "{\"title\":\"" + title + "\",\"description\":\"" + description + "\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        var resource = JSON.readTree(created.body());
        assertThat(resource.propertyNames()).containsExactlyInAnyOrder("id", "projectId", "title", "description", "status", "version", "createdAt", "updatedAt");
        assertThat(resource.path("title").asText()).isEqualTo(title);
        assertThat(resource.path("description").asText()).isEqualTo(description);
        for (String timestamp : List.of("createdAt", "updatedAt"))
            assertThat(resource.path(timestamp).asText()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
        assertThat(taskCreate(A, parent, key(), "{\"title\":\"" + title + "\"}").statusCode()).isEqualTo(201);
        assertProblem(taskCreate(A, parent, writeKey, "{\"title\":\"Changed\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(taskCreate(A, parent, null, "{\"title\":\"ok\"}"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        for (String invalid : List.of("bad", UUID.randomUUID().toString(), key().toUpperCase(Locale.ROOT))) {
            assertProblem(taskCreate(A, parent, invalid, "{\"title\":\"ok\"}"), 400, "IDEMPOTENCY_KEY_INVALID");
            assertProblem(request("GET", parent + "/tasks/" + invalid, token(A, false), null, null), 400, "VALIDATION_FAILED");
            assertProblem(request("GET", "/api/v1/projects/" + invalid + "/tasks", token(A, false), null, null), 400, "VALIDATION_FAILED");
        }
    }

    @Test void taskPagesHaveStableOrderAndRejectForeignOrInvalidCursors() throws Exception {
        String parent = projectPath(A), otherA = projectPath(A), otherB = projectPath(B);
        String path = parent + "/tasks";
        var empty = JSON.readTree(request("GET", path, token(A, false), null, null).body());
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            var created = taskCreate(A, parent, key(), "{\"title\":\"Duplicate\"}");
            assertThat(created.statusCode()).isEqualTo(201);
            expected.add(JSON.readTree(created.body()).path("id").asText());
        }
        expected.sort(String::compareTo);
        var first = JSON.readTree(request("GET", path, token(A, false), null, null).body());
        assertThat(first.path("items")).hasSize(50);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asText();
        List<String> actual = new ArrayList<>();
        first.path("items").forEach(item -> actual.add(item.path("id").asText()));
        var last = JSON.readTree(request("GET", path + "?cursor=" + cursor + "&limit=100", token(A, false), null, null).body());
        assertThat(last.path("items")).hasSize(51);
        last.path("items").forEach(item -> actual.add(item.path("id").asText()));
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(last.path("hasMore").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
        var maximum = JSON.readTree(request("GET", path + "?limit=100", token(A, false), null, null).body());
        assertThat(maximum.path("items")).hasSize(100);
        var one = JSON.readTree(request("GET", path + "?limit=1", token(A, false), null, null).body());
        assertThat(one.path("items")).hasSize(1);
        for (String other : List.of(otherA, otherB))
            assertProblem(request("GET", other + "/tasks?cursor=" + cursor, token(other.equals(otherB) ? B : A, false), null, null), 400, "VALIDATION_FAILED");
        assertProblem(request("GET", path + "?cursor=" + cursor, token(B, false), null, null), 400, "VALIDATION_FAILED");
        for (String query : List.of("limit=0", "limit=101", "limit=-1", "limit=1.5", "limit=word", "limit=9999999999",
                "limit=", "limit=1&limit=2", "cursor=", "cursor=bad", "sort=-id", "cursor=" + "x".repeat(2049)))
            assertProblem(request("GET", path + "?" + query, token(A, false), null, null), 400, "VALIDATION_FAILED");
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String expired = decoded.replaceFirst("\\n[^\\n]+\\n", "\n2000-01-01T00:00:00Z\n");
        String expiredCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(expired.getBytes(StandardCharsets.UTF_8));
        assertProblem(request("GET", path + "?cursor=" + expiredCursor, token(A, false), null, null), 400, "VALIDATION_FAILED");
        assertThat(JSON.readTree(request("GET", otherB + "/tasks", token(B, false), null, null).body()).path("items")).isEmpty();
    }

    @Test void taskRlsAndCompositeForeignKeyProtectDirectRuntimeAccess() throws Exception {
        String parentA = projectPath(A), parentB = projectPath(B);
        String idA = parentA.substring(parentA.lastIndexOf('/') + 1), idB = parentB.substring(parentB.lastIndexOf('/') + 1);
        assertThat(taskCreate(A, parentA, key(), "{\"title\":\"A\"}").statusCode()).isEqualTo(201);
        assertThat(taskCreate(B, parentB, key(), "{\"title\":\"B\"}").statusCode()).isEqualTo(201);
        try (var connection = runtimeConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM tasks")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM pg_class WHERE relname='tasks' AND relrowsecurity AND relforcerowsecurity AND pg_get_userbyid(relowner)='project_migrator'")).isEqualTo(1);
            for (String context : List.of("", "invalid", A)) {
                connection.setAutoCommit(false);
                try {
                    setTenant(connection, context);
                    if (context.equals("invalid")) {
                        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> scalar(connection, "SELECT count(*) FROM tasks"));
                        connection.rollback(); setTenant(connection, context);
                    } else if (context.isEmpty()) assertThat(scalar(connection, "SELECT count(*) FROM tasks")).isZero();
                    else {
                        assertThat(scalar(connection, "SELECT count(DISTINCT tenant_id) FROM tasks")).isEqualTo(1);
                        assertThat(scalar(connection, "SELECT count(*) FROM tasks WHERE tenant_id='" + B + "'")).isZero();
                    }
                    org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                            "INSERT INTO tasks(tenant_id,project_id,title) VALUES ('" + B + "','" + idB + "','Denied')"));
                } finally { connection.rollback(); connection.setAutoCommit(true); }
            }
            connection.setAutoCommit(false);
            try {
                setTenant(connection, A);
                var foreign = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO tasks(tenant_id,project_id,title) VALUES ('" + A + "','" + idB + "','Foreign')"));
                assertThat(foreign.getSQLState()).isEqualTo("23503");
                connection.rollback(); setTenant(connection, A);
                var missing = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO tasks(tenant_id,project_id,title) VALUES ('" + A + "','" + key() + "','Missing')"));
                assertThat(missing.getSQLState()).isEqualTo("23503");
                connection.rollback(); setTenant(connection, A);
                var update = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE tasks SET tenant_id='" + B + "',project_id='" + idB + "' WHERE project_id='" + idA + "'"));
                assertThat(update.getSQLState()).isEqualTo("42501");
            } finally { connection.rollback(); connection.setAutoCommit(true); }
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection, "ALTER TABLE tasks DISABLE ROW LEVEL SECURITY"));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(connection, "SET ROLE project_migrator"));
        }
        try (var migration = migratorConnection()) {
            var deletion = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(migration,
                    "DELETE FROM projects WHERE id='" + idA + "'"));
            assertThat(deletion.getSQLState()).isEqualTo("23001");
        }
        assertThat(JSON.readTree(request("GET", parentA + "/tasks", token(A, false), null, null).body()).path("items")).hasSize(1);
    }

    @Test void taskTransactionsClearContextOnCommitAndRollbackAndExpireResults() throws Exception {
        String parentA = projectPath(A), parentB = projectPath(B);
        var pool = app.getBean(javax.sql.DataSource.class);
        long pid;
        try (var connection = pool.getConnection()) { pid = scalar(connection, "SELECT pg_backend_pid()"); }
        String expiryKey = key();
        var committed = taskCreate(A, parentA, expiryKey, "{\"title\":\"Committed\"}");
        assertThat(committed.statusCode()).isEqualTo(201);
        try (var connection = pool.getConnection()) {
            assertThat(scalar(connection, "SELECT pg_backend_pid()")).isEqualTo(pid);
            assertThat(scalar(connection, "SELECT count(*) FROM tasks")).isZero();
        }
        String retryKey = key();
        try (var migration = migratorConnection()) {
            execute(migration, "CREATE FUNCTION reject_task_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test infrastructure outage'; END $$");
            execute(migration, "CREATE TRIGGER reject_task_test BEFORE INSERT ON tasks FOR EACH ROW EXECUTE FUNCTION reject_task_test()");
            try {
                assertProblem(taskCreate(A, parentA, retryKey, "{\"title\":\"Rollback\"}"), 503, "INFRASTRUCTURE_UNAVAILABLE");
            } finally {
                execute(migration, "DROP TRIGGER reject_task_test ON tasks");
                execute(migration, "DROP FUNCTION reject_task_test()");
            }
        }
        try (var connection = pool.getConnection()) {
            assertThat(scalar(connection, "SELECT pg_backend_pid()")).isEqualTo(pid);
            assertThat(scalar(connection, "SELECT count(*) FROM tasks")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM project_write_results")).isZero();
        }
        var recovered = taskCreate(B, parentB, retryKey, "{\"title\":\"Rollback\"}");
        assertThat(recovered.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(request("GET", parentA + "/tasks", token(A, false), null, null).body()).path("items")).hasSize(1);
        assertThat(JSON.readTree(request("GET", parentB + "/tasks", token(B, false), null, null).body()).path("items")).hasSize(1);
        try (var migration = migratorConnection()) {
            execute(migration, "UPDATE project_write_results SET completed_at=clock_timestamp()-interval '23 hours' WHERE idempotency_key='" + expiryKey + "'");
            assertThat(taskCreate(A, parentA, expiryKey, "{\"title\":\"Committed\"}").body()).isEqualTo(committed.body());
            execute(migration, "UPDATE project_write_results SET completed_at=clock_timestamp()-interval '25 hours' WHERE idempotency_key='" + expiryKey + "'");
        }
        var expired = taskCreate(A, parentA, expiryKey, "{\"title\":\"Committed\"}");
        assertThat(expired.statusCode()).isEqualTo(201);
        assertThat(expired.body()).isNotEqualTo(committed.body());
        assertThat(JSON.readTree(request("GET", parentA + "/tasks", token(A, false), null, null).body()).path("items")).hasSize(2);
    }

    @Test void stableTaskFailurePreservesOriginalTraceContextWhenReplayed() throws Exception {
        String parent = "/api/v1/projects/" + key(), writeKey = key();
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        var failed = HTTP.send(HttpRequest.newBuilder(URI.create(base + parent + "/tasks"))
                .header("Authorization", token(A, false)).header("Idempotency-Key", writeKey)
                .header("Content-Type", "application/json").header("traceparent", "00-" + trace + "-1234567890123456-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Missing parent\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(failed, 404, "PROJECT_NOT_FOUND");
        assertThat(JSON.readTree(failed.body()).path("traceId").asText()).isEqualTo(trace);
        var replay = taskCreate(A, parent, writeKey, "{\"title\":\"Missing parent\"}");
        assertThat(replay.body()).isEqualTo(failed.body());
    }

    static String projectPath(String tenant) throws Exception {
        var result = create(tenant, key(), "{\"name\":\"Task parent\"}");
        assertThat(result.statusCode()).isEqualTo(201);
        return result.headers().firstValue("Location").orElseThrow();
    }
    static HttpResponse<String> taskCreate(String tenant, String parent, String key, String body) throws Exception {
        return request("POST", parent + "/tasks", token(tenant, false), key, body);
    }

    static Connection runtimeConnection() throws SQLException { return DriverManager.getConnection(databaseUrl(), "project_app", "test-app"); }
    static Connection migratorConnection() throws SQLException { return DriverManager.getConnection(databaseUrl(), "project_migrator", "test-migrator"); }
    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getLong(1);
        }
    }
    static void setTenant(Connection connection, String tenant) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenant); statement.execute();
        }
    }

    static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    static HttpResponse<String> create(String tenant, String key, String body) throws Exception {
        return request("POST", "/api/v1/projects", token(tenant, false), key, body);
    }
    static HttpResponse<String> request(String method, String path, String token, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) builder.header("Authorization", token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        if (method.equals("PUT")) builder.header("If-Match", "\"1\"");
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String databaseUrl() { return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/project_db"; }
    static String key() { return "019535d9-3df7-7" + UUID.randomUUID().toString().substring(15); }
    static String token(String tenant, boolean service) throws Exception { return token(tenant, service, ID); }
    static String token(String tenant, boolean service, String identity) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("example-test").audience("saas.forge-api")
                .issueTime(java.util.Date.from(now)).expirationTime(java.util.Date.from(now.plusSeconds(service ? 300 : 900))).jwtID(key());
        if (service) claims.subject(ID).claim("client_id", ID).claim("scope", "runtime:read");
        else {
            claims.claim("identityId", identity);
            if (tenant != null) claims.claim("membershipId", tenant).claim("tenantId", tenant);
        }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .type(service ? new JOSEObjectType("at+jwt") : JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return "Bearer " + jwt.serialize();
    }
    @Test void taskCanBeUpdatedReopenedAndPermanentlyDeletedWithReplay() throws Exception {
        String parent = create(A, key(), "{\"name\":\"Task writes\"}").headers().firstValue("Location").orElseThrow();
        var created = taskCreate(A, parent, key(), "{\"title\":\"Original\",\"description\":\"Old\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        var original = JSON.readTree(created.body());
        long version = 1;
        // 覆盖三种状态的全部有向转换，包括已完成后重新打开。
        for (String status : List.of("IN_PROGRESS", "DONE", "TODO", "DONE", "IN_PROGRESS", "TODO")) {
            String writeKey = key();
            String body = "{\"title\":\"Updated\",\"status\":\"" + status + "\"}";
            var changed = update(A, path, writeKey, "\"" + version + "\"", body);
            assertThat(changed.statusCode()).isEqualTo(200);
            var task = JSON.readTree(changed.body());
            assertThat(task.path("status").asText()).isEqualTo(status);
            assertThat(task.path("version").asLong()).isEqualTo(++version);
            assertThat(task.path("title").asText()).isEqualTo("Updated");
            assertThat(task.path("description").isNull()).isTrue();
            for (String field : List.of("id", "projectId", "createdAt"))
                assertThat(task.path(field)).isEqualTo(original.path(field));
            assertThat(update(A, path, writeKey, "\"" + (version - 1) + "\"", body).body()).isEqualTo(changed.body());
            assertThat(request("GET", path, token(A, false), null, null).body()).isEqualTo(changed.body());
        }
        String staleKey = key();
        var stale = deleteResource(A, path, staleKey, "\"1\"");
        assertProblem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertThat(deleteResource(A, path, staleKey, "\"1\"").body()).isEqualTo(stale.body());
        assertThat(JSON.readTree(request("GET", path, token(A, false), null, null).body()).path("version").asLong()).isEqualTo(version);
        String deleteKey = key();
        var deleted = deleteResource(A, path, deleteKey, "\"" + version + "\"");
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleteResource(A, path, deleteKey, "\"" + version + "\"").statusCode()).isEqualTo(204);
        assertProblem(request("GET", path, token(A, false), null, null), 404, "TASK_NOT_FOUND");
        assertProblem(deleteResource(A, path, key(), "\"" + version + "\""), 404, "TASK_NOT_FOUND");
        assertThat(JSON.readTree(request("GET", parent + "/tasks", token(A, false), null, null).body()).path("items")).isEmpty();
    }

    static HttpResponse<String> deleteResource(String tenant, String path, String key, String version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", token(tenant, false));
        if (key != null) builder.header("Idempotency-Key", key);
        if (version != null) builder.header("If-Match", version);
        return HTTP.send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void taskWriteValidationDoesNotReserveKeysOrAllowImmutableInputs() throws Exception {
        String parent = projectPath(A);
        var created = taskCreate(A, parent, key(), "{\"title\":\"Original\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        String writeKey = key();
        String valid = "{\"title\":\"Valid\",\"status\":\"DONE\"}";
        for (String body : List.of("{}", "{\"title\":\"ok\"}", "{\"title\":\"ok\",\"status\":null}",
                "{\"title\":\"ok\",\"status\":\"done\"}", "{\"title\":\"ok\",\"status\":\"INVALID\"}",
                "{\"title\":\"ok\",\"status\":1}", "{\"title\":\"ok\",\"status\":\"1\"}",
                "{\"title\":\"ok\",\"status\":\" DONE \"}", "{\"title\":42,\"status\":\"TODO\"}",
                "{\"title\":\"   \",\"status\":\"TODO\"}", "{\"title\":\"a\\u0000b\",\"status\":\"TODO\"}",
                "{\"title\":\"" + "x".repeat(201) + "\",\"status\":\"TODO\"}",
                "{\"title\":\"ok\",\"description\":\"" + "x".repeat(2001) + "\",\"status\":\"TODO\"}",
                "{\"title\":\"ok\",\"description\":\"\\u0000\",\"status\":\"TODO\"}"))
            assertProblem(update(A, path, writeKey, "\"1\"", body), 400, "VALIDATION_FAILED");
        for (String field : List.of("id", "tenantId", "tenant_id", "projectId", "project_id", "version", "createdAt", "updatedAt"))
            assertProblem(update(A, path, writeKey, "\"1\"", valid.substring(0, valid.length()-1) + ",\"" + field + "\":null}"), 400, "VALIDATION_FAILED");
        for (String method : List.of("PUT", "DELETE")) {
            assertProblem(method.equals("PUT") ? update(A, path, writeKey, null, valid) : deleteResource(A, path, writeKey, null), 428, "VERSION_REQUIRED");
            assertProblem(method.equals("PUT") ? update(A, path, null, "\"1\"", valid) : deleteResource(A, path, null, "\"1\""), 400, "IDEMPOTENCY_KEY_REQUIRED");
            for (String version : List.of("1", "\"0\"", "\"-1\"", "*", "W/\"1\"", "\"1\",\"2\"", "\"9223372036854775808\""))
                assertProblem(method.equals("PUT") ? update(A, path, writeKey, version, valid) : deleteResource(A, path, writeKey, version), 400, "VALIDATION_FAILED");
            for (String invalidKey : List.of("bad", UUID.randomUUID().toString(), key().toUpperCase(Locale.ROOT)))
                assertProblem(method.equals("PUT") ? update(A, path, invalidKey, "\"1\"", valid) : deleteResource(A, path, invalidKey, "\"1\""), 400, "IDEMPOTENCY_KEY_INVALID");
        }
        assertThat(request("GET", path, token(A, false), null, null).body()).isEqualTo(created.body());
        var changed = update(A, path, writeKey, "\"1\"", "{\"title\":\"" + "😀".repeat(200) + "\",\"description\":\"" + "😀".repeat(2000) + "\",\"status\":\"DONE\"}");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(changed.body()).path("updatedAt").asText()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
        assertThat(deleteResource(A, path, key(), "\"2\"").statusCode()).isEqualTo(204);
    }

    @Test void taskWritesRejectForeignTenantsParentsAndUntrustedContexts() throws Exception {
        String parentA = projectPath(A), parentB = projectPath(B), otherA = projectPath(A);
        var a = taskCreate(A, parentA, key(), "{\"title\":\"A\"}");
        var b = taskCreate(B, parentB, key(), "{\"title\":\"B\"}");
        String pathA = a.headers().firstValue("Location").orElseThrow(), pathB = b.headers().firstValue("Location").orElseThrow();
        String body = "{\"title\":\"Changed\",\"status\":\"IN_PROGRESS\"}";
        for (String method : List.of("PUT", "DELETE")) {
            for (String[] attempt : List.of(new String[]{B, pathA, "PROJECT_NOT_FOUND"}, new String[]{A, pathB, "PROJECT_NOT_FOUND"},
                    new String[]{A, otherA + pathA.substring(parentA.length()), "TASK_NOT_FOUND"},
                    new String[]{A, parentA + "/tasks/" + key(), "TASK_NOT_FOUND"})) {
                String writeKey = key();
                var denied = method.equals("PUT") ? update(attempt[0], attempt[1], writeKey, "\"1\"", body) : deleteResource(attempt[0], attempt[1], writeKey, "\"1\"");
                assertProblem(denied, 404, attempt[2]);
                var replay = method.equals("PUT") ? update(attempt[0], attempt[1], writeKey, "\"1\"", body) : deleteResource(attempt[0], attempt[1], writeKey, "\"1\"");
                assertThat(replay.body()).isEqualTo(denied.body());
            }
            String platformToken = token(null, false);
            for (String credential : Arrays.asList(null, "Bearer invalid", token(null, true), platformToken)) {
                var builder = HttpRequest.newBuilder(URI.create(base + pathA)).header("Idempotency-Key", key())
                        .header("If-Match", "\"1\"").header("Content-Type", "application/json");
                if (credential != null) builder.header("Authorization", credential);
                var denied = HTTP.send(builder.method(method, method.equals("PUT") ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertProblem(denied, credential != null && credential.equals(platformToken) ? 403 : 401,
                        credential != null && credential.equals(platformToken) ? "ACCESS_CONTEXT_UNAVAILABLE" : "ACCESS_TOKEN_INVALID");
            }
            for (String input : List.of("header", "query")) {
                var builder = HttpRequest.newBuilder(URI.create(base + pathA + (input.equals("query") ? "?tenantId=" + B : "")))
                        .header("Authorization", token(A, false)).header("Idempotency-Key", key()).header("If-Match", "\"1\"")
                        .header("Content-Type", "application/json");
                if (input.equals("header")) builder.header("X-Tenant-Id", B);
                var denied = HTTP.send(builder.method(method, method.equals("PUT") ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertProblem(denied, 400, input.equals("header") ? "UNTRUSTED_CONTEXT_HEADER" : "VALIDATION_FAILED");
            }
        }
        assertThat(request("GET", pathA, token(A, false), null, null).body()).isEqualTo(a.body());
        assertThat(request("GET", pathB, token(B, false), null, null).body()).isEqualTo(b.body());
        for (String[] own : List.of(new String[]{A, pathA}, new String[]{B, pathB})) {
            String writeKey = key();
            var changed = update(own[0], own[1], writeKey, "\"1\"", body);
            assertThat(changed.statusCode()).isEqualTo(200);
            assertProblem(update(own[0].equals(A) ? B : A, own[1], writeKey, "\"1\"", body), 409, "IDEMPOTENCY_KEY_REUSED");
            assertProblem(deleteResource(own[0], own[1], writeKey, "\"2\""), 409, "IDEMPOTENCY_KEY_REUSED");
            assertProblem(taskCreate(own[0], own[0].equals(A) ? parentA : parentB, writeKey, "{\"title\":\"Changed\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
            assertProblem(create(own[0], writeKey, "{\"name\":\"Changed\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
            String deleteKey = key();
            assertThat(deleteResource(own[0], own[1], deleteKey, "\"2\"").statusCode()).isEqualTo(204);
            assertProblem(deleteResource(own[0].equals(A) ? B : A, own[1], deleteKey, "\"2\""), 409, "IDEMPOTENCY_KEY_REUSED");
            assertProblem(update(own[0], own[1], deleteKey, "\"2\"", body), 409, "IDEMPOTENCY_KEY_REUSED");
        }
    }

    @Test void taskInfrastructureFailuresRollBackMutationAndReleaseKey() throws Exception {
        String parent = projectPath(A);
        var created = taskCreate(A, parent, key(), "{\"title\":\"Rollback\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        String id = JSON.readTree(created.body()).path("id").asText();
        long version = 1;
        for (String method : List.of("UPDATE", "DELETE")) {
            String writeKey = key(), tag = "\"" + version + "\"";
            String before = request("GET", path, token(A, false), null, null).body();
            try (var migration = migratorConnection()) {
                // 在实际变更之后制造失败，验证数据与幂等完成记录整体回滚。
                execute(migration, "CREATE FUNCTION fail_task_write_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test outage'; END $$");
                execute(migration, "CREATE TRIGGER fail_task_write_test AFTER " + method + " ON tasks FOR EACH ROW EXECUTE FUNCTION fail_task_write_test()");
                try {
                    assertProblem(method.equals("UPDATE") ? update(A, path, writeKey, tag, "{\"title\":\"Retry\",\"status\":\"DONE\"}") : deleteResource(A, path, writeKey, tag), 503, "INFRASTRUCTURE_UNAVAILABLE");
                } finally {
                    execute(migration, "DROP TRIGGER fail_task_write_test ON tasks");
                    execute(migration, "DROP FUNCTION fail_task_write_test()");
                }
                assertThat(scalar(migration, "SELECT count(*) FROM project_write_results WHERE idempotency_key='" + writeKey + "'")).isZero();
                assertThat(scalar(migration, "SELECT count(*) FROM tasks WHERE id='" + id + "' AND version=" + version)).isEqualTo(1);
            }
            assertThat(request("GET", path, token(A, false), null, null).body()).isEqualTo(before);
            var retry = method.equals("UPDATE") ? update(A, path, writeKey, tag, "{\"title\":\"Retry\",\"status\":\"DONE\"}") : deleteResource(A, path, writeKey, tag);
            assertThat(retry.statusCode()).isEqualTo(method.equals("UPDATE") ? 200 : 204);
            version++;
        }
        try (var migration = migratorConnection(); var runtime = runtimeConnection()) {
            assertThat(scalar(migration, "SELECT count(*) FROM tasks WHERE id='" + id + "'")).isZero();
            assertThat(scalar(runtime, "SELECT count(*) FROM tasks")).isZero();
        }
    }

    @Test void taskRuntimeWritesCannotBypassRlsOrChangeOwnership() throws Exception {
        String parentA = projectPath(A), parentB = projectPath(B);
        String pathA = taskCreate(A, parentA, key(), "{\"title\":\"RLS A\"}").headers().firstValue("Location").orElseThrow();
        String pathB = taskCreate(B, parentB, key(), "{\"title\":\"RLS B\"}").headers().firstValue("Location").orElseThrow();
        try (var runtime = runtimeConnection()) {
            for (String tenant : Arrays.asList(null, "", "invalid", A, B)) {
                runtime.setAutoCommit(false);
                try {
                    if (tenant != null) setTenant(runtime, tenant);
                    if ("invalid".equals(tenant)) {
                        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "UPDATE tasks SET status='DONE'"));
                        runtime.rollback(); setTenant(runtime, tenant);
                        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "DELETE FROM tasks"));
                    } else {
                        long visible = scalar(runtime, "SELECT count(*) FROM tasks");
                        if (tenant == null || tenant.isEmpty()) assertThat(visible).isZero();
                        try (var statement = runtime.createStatement()) {
                            assertThat(statement.executeUpdate("UPDATE tasks SET status='DONE'")).isEqualTo((int) visible);
                            assertThat(statement.executeUpdate("DELETE FROM tasks")).isEqualTo((int) visible);
                        }
                        if (tenant != null && !tenant.isEmpty()) {
                            runtime.rollback(); setTenant(runtime, tenant);
                            String foreign = tenant.equals(A) ? B : A;
                            try (var statement = runtime.createStatement()) {
                                assertThat(statement.executeUpdate("UPDATE tasks SET title='Intrusion' WHERE tenant_id='" + foreign + "'")).isZero();
                                assertThat(statement.executeUpdate("DELETE FROM tasks WHERE tenant_id='" + foreign + "'")).isZero();
                            }
                        }
                    }
                } finally { runtime.rollback(); runtime.setAutoCommit(true); }
                assertThat(scalar(runtime, "SELECT count(*) FROM tasks")).isZero();
            }
            for (String field : List.of("id", "tenant_id", "project_id", "created_at")) {
                runtime.setAutoCommit(false);
                try {
                    setTenant(runtime, A);
                    var denied = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                            () -> execute(runtime, "UPDATE tasks SET " + field + "=" + field));
                    assertThat(denied.getSQLState()).isEqualTo("42501");
                } finally { runtime.rollback(); runtime.setAutoCommit(true); }
            }
        }
        assertThat(JSON.readTree(request("GET", pathA, token(A, false), null, null).body()).path("status").asText()).isEqualTo("TODO");
        assertThat(JSON.readTree(request("GET", pathB, token(B, false), null, null).body()).path("status").asText()).isEqualTo("TODO");
    }

    @Test @Order(Integer.MAX_VALUE - 1) void taskConcurrentUpdatesHaveOneWinnerAndAllowConflictRecovery() throws Exception {
        var created = taskCreate(A, projectPath(A), key(), "{\"title\":\"Concurrent update\"}");
        String path = created.headers().firstValue("Location").orElseThrow();
        String id = JSON.readTree(created.body()).path("id").asText();
        var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        String firstKey = key();
        String secondKey = key();
        try (var migration = migratorConnection()) {
            migration.setAutoCommit(false);
            execute(migration, "SELECT id FROM tasks WHERE id='" + id + "' FOR UPDATE");
            var first = workers.submit(() -> update(A, path, firstKey, "\"1\"", "{\"title\":\"Winner A\",\"status\":\"IN_PROGRESS\"}"));
            String otherIdentity = key();
            var second = workers.submit(() -> HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", token(A, false, otherIdentity)).header("Idempotency-Key", secondKey)
                    .header("If-Match", "\"1\"").header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"title\":\"Winner B\",\"status\":\"DONE\"}")).build(), HttpResponse.BodyHandlers.ofString()));
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                boolean bothWaiting = false;
                while (System.nanoTime() < deadline && !bothWaiting) {
                    try (var observer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                        bothWaiting = scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE usename='project_app' AND wait_event_type='Lock'") == 2;
                    }
                    if (!bothWaiting) Thread.sleep(20);
                }
                assertThat(bothWaiting).as("both HTTP writes reached the locked resource with independent connections").isTrue();
            } finally { migration.rollback(); }
            var responses = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            var winner = responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow();
            var loser = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertProblem(loser, 409, "RESOURCE_VERSION_CONFLICT");
            var firstReplay = update(A, path, firstKey, "\"1\"", "{\"title\":\"Winner A\",\"status\":\"IN_PROGRESS\"}");
            assertThat(firstReplay.body()).isEqualTo(responses.get(0).body());
            var secondReplay = HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", token(A, false, otherIdentity)).header("Idempotency-Key", secondKey)
                    .header("If-Match", "\"1\"").header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"title\":\"Winner B\",\"status\":\"DONE\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(secondReplay.body()).isEqualTo(responses.get(1).body());
            var read = request("GET", path, token(A, false), null, null);
            assertThat(read.body()).isEqualTo(winner.body());
            assertThat(JSON.readTree(read.body()).path("version").asLong()).isEqualTo(2);
            assertThat(update(A, path, key(), "\"2\"", "{\"title\":\"Recovered\",\"status\":\"TODO\"}").statusCode()).isEqualTo(200);
        } finally { workers.shutdownNow(); pool.setMaximumPoolSize(1); }
    }


    @Test @Order(Integer.MAX_VALUE - 2) void taskWritesReturnInProgressThenReplayAndNormalizeOptionalDescription() throws Exception {
        var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(2);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (String method : List.of("PUT", "DELETE")) {
                String parent = projectPath(A);
                var created = taskCreate(A, parent, key(), "{\"title\":\"Pending write\"}");
                String path = created.headers().firstValue("Location").orElseThrow();
                String id = JSON.readTree(created.body()).path("id").asText();
                String writeKey = key(), body = "{\"title\":\"Updated\",\"status\":\"DONE\"}";
                try (var migration = migratorConnection()) {
                    migration.setAutoCommit(false);
                    execute(migration, "SELECT id FROM tasks WHERE id='" + id + "' FOR UPDATE");
                    var first = worker.submit(() -> method.equals("PUT") ? update(A, path, writeKey, "\"1\"", body) : deleteResource(A, path, writeKey, "\"1\""));
                    try {
                        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                        boolean waiting = false;
                        while (System.nanoTime() < deadline && !waiting) {
                            try (var observer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                                waiting = scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE usename='project_app' AND wait_event_type='Lock'") > 0;
                            }
                            if (!waiting) Thread.sleep(20);
                        }
                        assertThat(waiting).as("HTTP Task write reached the held resource lock").isTrue();
                        var duplicate = method.equals("PUT") ? update(A, path, writeKey, "\"1\"", body) : deleteResource(A, path, writeKey, "\"1\"");
                        assertProblem(duplicate, 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
                        assertThat(duplicate.headers().firstValue("Retry-After")).contains("1");
                    } finally { migration.rollback(); }
                    var completed = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(completed.statusCode()).isEqualTo(method.equals("PUT") ? 200 : 204);
                    var replay = method.equals("PUT") ? update(A, path, writeKey, "\"1\"", "{\"description\":null,\"status\":\"DONE\",\"title\":\"Updated\"}") : deleteResource(A, path, writeKey, "\"1\"");
                    assertThat(replay.statusCode()).isEqualTo(completed.statusCode());
                    assertThat(replay.body()).isEqualTo(completed.body());
                    assertProblem(method.equals("PUT") ? update(A, path, writeKey, "\"2\"", body) : deleteResource(A, path, writeKey, "\"2\""), 409, "IDEMPOTENCY_KEY_REUSED");
                    if (method.equals("PUT")) {
                        assertProblem(update(A, path, writeKey, "\"1\"", "{\"title\":\"Different\",\"status\":\"DONE\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
                        assertThat(JSON.readTree(request("GET", path, token(A, false), null, null).body()).path("version").asLong()).isEqualTo(2);
                    }
                }
            }
        } finally { worker.shutdownNow(); pool.setMaximumPoolSize(1); }
    }

    @Test void projectDeletionRequiresEmptyProjectAndCurrentVersionAndReplaysResults() throws Exception {
        String parent = projectPath(A);
        var task = taskCreate(A, parent, key(), "{\"title\":\"Must remove first\"}");
        String taskPath = task.headers().firstValue("Location").orElseThrow();
        String before = request("GET", parent, token(A, false), null, null).body();
        String deniedKey = key();
        var denied = deleteResource(A, parent, deniedKey, "\"1\"");
        assertProblem(denied, 409, "PROJECT_NOT_EMPTY");
        assertThat(request("GET", parent, token(A, false), null, null).body()).isEqualTo(before);
        assertThat(request("GET", taskPath, token(A, false), null, null).body()).isEqualTo(task.body());
        assertThat(update(A, taskPath, key(), "\"1\"", "{\"title\":\"Finished\",\"status\":\"DONE\"}").statusCode()).isEqualTo(200);
        assertProblem(deleteResource(A, parent, key(), "\"1\""), 409, "PROJECT_NOT_EMPTY");
        assertThat(deleteResource(A, taskPath, key(), "\"2\"").statusCode()).isEqualTo(204);
        assertThat(deleteResource(A, parent, deniedKey, "\"1\"").body()).isEqualTo(denied.body());
        var changed = HTTP.send(HttpRequest.newBuilder(URI.create(base + parent))
                .header("Authorization", token(A, false, key())).header("Idempotency-Key", key())
                .header("If-Match", "\"1\"").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Changed by another member\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode()).isEqualTo(200);
        String staleKey = key();
        var stale = deleteResource(A, parent, staleKey, "\"1\"");
        assertProblem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertThat(request("GET", parent, token(A, false), null, null).body()).isEqualTo(changed.body());
        String deleteKey = key();
        var deleted = deleteResource(A, parent, deleteKey, "\"2\"");
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleteResource(A, parent, deleteKey, "\"2\"").statusCode()).isEqualTo(204);
        assertProblem(request("GET", parent, token(A, false), null, null), 404, "PROJECT_NOT_FOUND");
        assertProblem(deleteResource(A, parent, key(), "\"2\""), 404, "PROJECT_NOT_FOUND");
        assertThat(deleteResource(A, parent, staleKey, "\"1\"").body()).isEqualTo(stale.body());
        try (var migration = migratorConnection()) {
            assertThat(scalar(migration, "SELECT count(*) FROM projects WHERE id='" + parent.substring(parent.lastIndexOf('/') + 1) + "'")).isZero();
        }
    }

    @Test void projectDeleteValidationAndTenantSwitchCannotExposeOrRemoveForeignData() throws Exception {
        for (String tenant : List.of(A, B)) {
            String parent = projectPath(tenant), other = tenant.equals(A) ? B : A;
            String before = request("GET", parent, token(tenant, false), null, null).body();
            String writeKey = key();
            assertProblem(deleteResource(tenant, parent, null, "\"1\""), 400, "IDEMPOTENCY_KEY_REQUIRED");
            assertProblem(deleteResource(tenant, parent, "bad", "\"1\""), 400, "IDEMPOTENCY_KEY_INVALID");
            assertProblem(deleteResource(tenant, parent, writeKey, null), 428, "VERSION_REQUIRED");
            for (String version : List.of("1", "*", "W/\"1\"", "\"0\"", "\"9223372036854775808\"", "\"1\",\"2\""))
                assertProblem(deleteResource(tenant, parent, writeKey, version), 400, "VALIDATION_FAILED");
            for (String credential : Arrays.asList(null, "Bearer invalid", token(null, true), token(null, false))) {
                var builder = HttpRequest.newBuilder(URI.create(base + parent)).header("Idempotency-Key", writeKey).header("If-Match", "\"1\"");
                if (credential != null) builder.header("Authorization", credential);
                boolean platform = credential != null && !credential.equals("Bearer invalid")
                        && SignedJWT.parse(credential.substring(7)).getJWTClaimsSet().getClaim("identityId") != null;
                assertProblem(HTTP.send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString()),
                        platform ? 403 : 401, platform ? "ACCESS_CONTEXT_UNAVAILABLE" : "ACCESS_TOKEN_INVALID");
            }
            assertProblem(deleteResource(tenant, parent + "?tenantId=" + other, writeKey, "\"1\""), 400, "VALIDATION_FAILED");
            var forged = HTTP.send(HttpRequest.newBuilder(URI.create(base + parent)).header("Authorization", token(tenant, false))
                    .header("Idempotency-Key", writeKey).header("If-Match", "\"1\"").header("X-Tenant-Id", other).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertProblem(forged, 400, "UNTRUSTED_CONTEXT_HEADER");
            String foreignKey = key();
            var foreign = deleteResource(other, parent, foreignKey, "\"1\"");
            assertProblem(foreign, 404, "PROJECT_NOT_FOUND");
            assertProblem(deleteResource(tenant, parent, foreignKey, "\"1\""), 409, "IDEMPOTENCY_KEY_REUSED");
            assertThat(request("GET", parent, token(tenant, false), null, null).body()).isEqualTo(before);
            assertThat(deleteResource(tenant, parent, writeKey, "\"1\"").statusCode()).isEqualTo(204);
            assertProblem(deleteResource(other, parent, writeKey, "\"1\""), 409, "IDEMPOTENCY_KEY_REUSED");
            assertThat(deleteResource(tenant, parent, writeKey, "\"1\"").statusCode()).isEqualTo(204);
            assertProblem(deleteResource(tenant, parent, writeKey, "\"2\""), 409, "IDEMPOTENCY_KEY_REUSED");
            assertProblem(create(tenant, writeKey, "{\"name\":\"Different operation\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
            assertThat(deleteResource(other, parent, foreignKey, "\"1\"").body()).isEqualTo(foreign.body());
        }
    }

    @Test void projectDeleteInfrastructureFailureRollsBackDataAndReleasesKey() throws Exception {
        String parent = projectPath(A), writeKey = key();
        String before = request("GET", parent, token(A, false), null, null).body();
        try (var migration = migratorConnection()) {
            execute(migration, "CREATE FUNCTION fail_project_delete_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test outage'; END $$");
            execute(migration, "CREATE TRIGGER fail_project_delete_test AFTER DELETE ON projects FOR EACH ROW EXECUTE FUNCTION fail_project_delete_test()");
            try { assertProblem(deleteResource(A, parent, writeKey, "\"1\""), 503, "INFRASTRUCTURE_UNAVAILABLE"); }
            finally {
                execute(migration, "DROP TRIGGER fail_project_delete_test ON projects");
                execute(migration, "DROP FUNCTION fail_project_delete_test()");
            }
            assertThat(scalar(migration, "SELECT count(*) FROM project_write_results WHERE idempotency_key='" + writeKey + "'")).isZero();
        }
        assertThat(request("GET", parent, token(A, false), null, null).body()).isEqualTo(before);
        assertThat(deleteResource(A, parent, writeKey, "\"1\"").statusCode()).isEqualTo(204);
        try (var connection = app.getBean(com.zaxxer.hikari.HikariDataSource.class).getConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM projects")).isZero();
        }
    }

    @Test void projectRuntimeDeletionRespectsRlsAndRestrictiveForeignKey() throws Exception {
        String a = projectPath(A), b = projectPath(B);
        String populated = projectPath(A);
        var task = taskCreate(A, populated, key(), "{\"title\":\"No cascade\"}");
        String populatedId = populated.substring(populated.lastIndexOf('/') + 1);
        try (var runtime = runtimeConnection()) {
            assertThat(scalar(runtime, "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls OR rolinherit)")).isZero();
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "SET ROLE project_migrator"));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "TRUNCATE projects CASCADE"));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "ALTER TABLE tasks DROP CONSTRAINT fk_tasks_tenant_project"));
            for (String tenant : Arrays.asList(null, "", "invalid", A, B)) {
                runtime.setAutoCommit(false);
                try {
                    if (tenant != null) setTenant(runtime, tenant);
                    if ("invalid".equals(tenant)) {
                        var failure = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "DELETE FROM projects"));
                        assertThat(failure.getSQLState()).isEqualTo("22P02");
                    } else {
                        String target = A.equals(tenant) ? b : a;
                        try (var statement = runtime.createStatement()) {
                            assertThat(statement.executeUpdate("DELETE FROM projects WHERE id='" + target.substring(target.lastIndexOf('/') + 1) + "'")).isZero();
                        }
                        if (A.equals(tenant)) {
                            var failure = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(runtime, "DELETE FROM projects"));
                            assertThat(failure.getSQLState()).isEqualTo("23001");
                        } else if (B.equals(tenant)) {
                            long visible = scalar(runtime, "SELECT count(*) FROM projects");
                            try (var statement = runtime.createStatement()) { assertThat(statement.executeUpdate("DELETE FROM projects")).isEqualTo((int) visible); }
                        } else {
                            try (var statement = runtime.createStatement()) { assertThat(statement.executeUpdate("DELETE FROM projects")).isZero(); }
                        }
                    }
                } finally { runtime.rollback(); }
                assertThat(scalar(runtime, "SELECT count(*) FROM projects")).isZero();
            }
        }
        try (var migration = migratorConnection()) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> execute(migration, "DELETE FROM projects WHERE id='" + populatedId + "'"));
            assertThat(failure.getSQLState()).isEqualTo("23001");
        }
        assertThat(request("GET", task.headers().firstValue("Location").orElseThrow(), token(A, false), null, null).body()).isEqualTo(task.body());
        assertThat(request("GET", a, token(A, false), null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", b, token(B, false), null, null).statusCode()).isEqualTo(200);
    }

    @Test @Order(Integer.MAX_VALUE - 3) void concurrentTaskCreationAndProjectDeletionSerializeInBothOrders() throws Exception {
        var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (boolean createFirst : List.of(true, false)) {
                String parent = projectPath(A), createKey = key(), deleteKey = key();
                String table = createFirst ? "tasks" : "projects", event = createFirst ? "INSERT" : "DELETE";
                try (var migration = migratorConnection()) {
                    // 仅暂停隔离测试数据库内的已变更事务，让另一 HTTP 请求真实进入父资源锁竞争。
                    execute(migration, "CREATE FUNCTION pause_parent_race_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(216042); RETURN NULL; END $$");
                    execute(migration, "CREATE TRIGGER pause_parent_race_test AFTER " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION pause_parent_race_test()");
                    migration.setAutoCommit(false);
                    execute(migration, "SELECT pg_advisory_xact_lock(216042)");
                    try {
                        var first = workers.submit(() -> createFirst ? taskCreate(A, parent, createKey, "{\"title\":\"Race\"}") : deleteResource(A, parent, deleteKey, "\"1\""));
                        awaitRuntimeLockWaiters(1);
                        var second = workers.submit(() -> createFirst ? deleteResource(A, parent, deleteKey, "\"1\"") : taskCreate(A, parent, createKey, "{\"title\":\"Race\"}"));
                        try { awaitRuntimeLockWaiters(2); } finally { migration.rollback(); }
                        var firstResult = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
                        var secondResult = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
                        if (createFirst) {
                            assertThat(firstResult.statusCode()).isEqualTo(201);
                            assertProblem(secondResult, 409, "PROJECT_NOT_EMPTY");
                            assertThat(request("GET", parent, token(A, false), null, null).statusCode()).isEqualTo(200);
                            assertThat(request("GET", firstResult.headers().firstValue("Location").orElseThrow(), token(A, false), null, null).body()).isEqualTo(firstResult.body());
                            assertThat(JSON.readTree(request("GET", parent + "/tasks", token(A, false), null, null).body()).path("items")).hasSize(1);
                        } else {
                            assertThat(firstResult.statusCode()).isEqualTo(204);
                            assertProblem(secondResult, 404, "PROJECT_NOT_FOUND");
                            assertProblem(request("GET", parent, token(A, false), null, null), 404, "PROJECT_NOT_FOUND");
                            assertThat(taskCreate(A, parent, createKey, "{\"title\":\"Race\"}").body()).isEqualTo(secondResult.body());
                        }
                        assertThat(scalar(migration, "SELECT count(*) FROM tasks t LEFT JOIN projects p ON (t.tenant_id,t.project_id)=(p.tenant_id,p.id) WHERE p.id IS NULL")).isZero();
                        if (!createFirst) assertThat(scalar(migration, "SELECT count(*) FROM tasks WHERE project_id='" + parent.substring(parent.lastIndexOf('/') + 1) + "'")).isZero();
                    } finally {
                        migration.rollback(); migration.setAutoCommit(true);
                        execute(migration, "DROP TRIGGER pause_parent_race_test ON " + table);
                        execute(migration, "DROP FUNCTION pause_parent_race_test()");
                    }
                }
            }
        } finally { workers.shutdownNow(); pool.setMaximumPoolSize(1); }
    }

    @Test @Order(Integer.MAX_VALUE - 3) void projectDeletionReturnsInProgressAndThenReplaysCommittedResult() throws Exception {
        String parent = projectPath(A), writeKey = key();
        String id = parent.substring(parent.lastIndexOf('/') + 1);
        var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(2);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var migration = migratorConnection()) {
            migration.setAutoCommit(false);
            execute(migration, "SELECT id FROM projects WHERE id='" + id + "' FOR UPDATE");
            var pending = worker.submit(() -> deleteResource(A, parent, writeKey, "\"1\""));
            try {
                awaitRuntimeLockWaiters(1);
                var duplicate = deleteResource(A, parent, writeKey, "\"1\"");
                assertProblem(duplicate, 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
                assertThat(duplicate.headers().firstValue("Retry-After")).contains("1");
            } finally { migration.rollback(); }
            assertThat(pending.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            var replay = deleteResource(A, parent, writeKey, "\"1\"");
            assertThat(replay.statusCode()).isEqualTo(204);
            assertThat(replay.body()).isEmpty();
        } finally { worker.shutdownNow(); pool.setMaximumPoolSize(1); }
    }

    static void awaitRuntimeLockWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (var observer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                if (scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE usename='project_app' AND wait_event_type='Lock'") == count) return;
            }
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assertions.fail("HTTP writes did not reach " + count + " independent database lock waits");
    }

}
