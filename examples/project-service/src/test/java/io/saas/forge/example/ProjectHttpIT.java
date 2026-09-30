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
                "--server.port=0", "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.discovery.client.simple.instances.iam-service[0].uri=http://127.0.0.1:" + jwks.getAddress().getPort(),
                "--security.jwt.issuer=example-test", "--saas.forge.environment=example-test",
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
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
}
