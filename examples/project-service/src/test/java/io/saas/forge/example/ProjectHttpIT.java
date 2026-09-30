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
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String databaseUrl() { return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/project_db"; }
    static String key() { return "019535d9-3df7-7" + UUID.randomUUID().toString().substring(15); }
    static String token(String tenant, boolean service) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("example-test").audience("saas.forge-api")
                .issueTime(java.util.Date.from(now)).expirationTime(java.util.Date.from(now.plusSeconds(service ? 300 : 900))).jwtID(key());
        if (service) claims.subject(ID).claim("client_id", ID).claim("scope", "runtime:read");
        else {
            claims.claim("identityId", ID);
            if (tenant != null) claims.claim("membershipId", tenant).claim("tenantId", tenant);
        }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .type(service ? new JOSEObjectType("at+jwt") : JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return "Bearer " + jwt.serialize();
    }
}
