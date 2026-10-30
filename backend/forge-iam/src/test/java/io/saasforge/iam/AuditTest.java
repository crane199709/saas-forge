package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.audit.service.AuditService;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * 审计（工单 23）：iam 自动产出登录与权限变更事件；事件写入按月分区的审计表并自动建分区；Tenant Admin 只能查到本租户的审计，
 * 平台审计与租户审计分开。
 *
 * <p>测试不连接 MQ：产出的事件停留在 Outbox 中，测试读出后交给与消费者相同的写入逻辑，模拟投递。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/audit.sql")
class AuditTest {
    static final String KEY_FILE = "target/test-audit-signing.jwk";
    static final String PASSWORD = "Demo-password-123!";
    static final String OPERATOR_PASSWORD = "Operator-password-123!";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final UUID TENANT_A = UUID.fromString("019a0110-0000-7000-8000-000000000210");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-000000000220");
    static final UUID ADMIN_A = UUID.fromString("019a0110-0000-7000-8000-000000000212");
    static final UUID MEMBER_A = UUID.fromString("019a0110-0000-7000-8000-000000000214");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-000000000222");
    static final UUID SUPER_ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN_A, UUID.fromString("019a0110-0000-7000-8000-000000000211"),
            MEMBER_A, UUID.fromString("019a0110-0000-7000-8000-000000000213"),
            ADMIN_B, UUID.fromString("019a0110-0000-7000-8000-000000000221"));
    static final Map<UUID, UUID> TENANTS = Map.of(ADMIN_A, TENANT_A, MEMBER_A, TENANT_A, ADMIN_B, TENANT_B);
    static final JsonMapper JSON = JsonMapper.builder().build();
    /** 另一个用例写入了未来月份的事件；只看当前事件的查询以此为上限，两个用例的执行顺序不影响结果。 */
    static final String BEFORE_FUTURE = "to=2030-01-01T00:00:00Z";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    AuditService audits;

    @Test
    void loginAndPermissionChangesAreAuditedAndTenantAdminsOnlySeeTheirOwnTenant() throws Exception {
        signIn("auditadmina");
        assertThat(status(post("/auth/login", Map.of("account", "auditadmina", "password", "wrong-password"))))
                .isEqualTo(401);
        assertThat(status(call(
                        ADMIN_A,
                        "POST",
                        "/roles",
                        Map.of("code", "auditor", "name", "审计员", "permissions", List.of("iam:audit:read")))))
                .isEqualTo(201);
        String roleB = (String) data(call(ADMIN_B, "POST", "/roles", Map.of("code", "auditor", "name", "乙审计员")))
                .get("id");

        List<AuditEvent> produced = deliver();
        AuditEvent login = single(produced, "auth.login", AuditOutcome.SUCCESS, AuditRealm.TENANT);
        assertThat(login.category()).isEqualTo(AuditCategory.LOGIN);
        assertThat(login.tenantId()).isEqualTo(TENANT_A);
        assertThat(login.actorId()).isEqualTo(IDENTITIES.get(ADMIN_A));
        assertThat(login.resourceId()).isEqualTo(ADMIN_A.toString());
        assertThat(login.service()).isEqualTo("forge-iam");
        // 登录失败发生在全局登录会话上，不属于任何租户；能确定账号所有者时记为操作人
        AuditEvent failed = single(produced, "auth.login", AuditOutcome.FAILURE, AuditRealm.TENANT);
        assertThat(failed.tenantId()).isNull();
        assertThat(failed.actorId()).isEqualTo(IDENTITIES.get(ADMIN_A));
        assertThat(failed.errorCode()).isEqualTo("auth.failed");
        assertThat(failed.detail()).containsEntry("account", "auditadmina");
        assertThat(produced)
                .filteredOn(event -> event.action().equals("role.create"))
                .extracting(AuditEvent::tenantId, AuditEvent::category)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(TENANT_A, AuditCategory.PERMISSION),
                        Tuple.tuple(TENANT_B, AuditCategory.PERMISSION));

        List<Map<String, Object>> ofA = events(ADMIN_A, "?" + BEFORE_FUTURE);
        assertThat(ofA).extracting(event -> event.get("action")).containsExactly("role.create", "auth.login");
        Map<String, Object> created = ofA.get(0);
        assertThat(created)
                .containsEntry("category", "permission")
                .containsEntry("outcome", "success")
                .containsEntry("actorAccount", "auditadmina")
                .containsEntry("actorName", "审计管理员甲")
                .containsEntry("resourceType", "role");
        assertThat(object(created.get("detail")))
                .containsEntry("code", "auditor")
                .containsEntry("permissions", List.of("iam:audit:read"));

        // 租户 A 的管理员查不到租户 B 的审计，按 B 的对象标识查询也查不到
        List<Map<String, Object>> ofB = events(ADMIN_B, "?" + BEFORE_FUTURE);
        assertThat(ofB).extracting(event -> event.get("action")).containsExactly("role.create");
        assertThat(ofB.get(0)).containsEntry("resourceId", roleB);
        assertThat(events(ADMIN_A, "?resourceId=" + roleB)).isEmpty();

        // 筛选：动作、操作人、时间与分页
        assertThat(events(ADMIN_A, "?action=auth.login&" + BEFORE_FUTURE)).hasSize(1);
        assertThat(events(ADMIN_A, "?actor=审计管理员&" + BEFORE_FUTURE)).hasSize(2);
        assertThat(events(ADMIN_A, "?actor=nobody")).isEmpty();
        assertThat(events(ADMIN_A, "?from=2000-01-01T00:00:00Z&to=2000-02-01T00:00:00Z"))
                .isEmpty();
        ResponseEntity<Map> page = call(ADMIN_A, "GET", "/audit/events?page=2&size=1&" + BEFORE_FUTURE, null);
        assertThat(list(page)).extracting(event -> object(event).get("action")).containsExactly("auth.login");
        assertThat(object(object(page.getBody().get("meta")).get("page")))
                .containsEntry("totalElements", 2)
                .containsEntry("totalPages", 2);

        // 没有 iam:audit:read 的成员不能查看审计
        assertThat(code(call(MEMBER_A, "GET", "/audit/events", null))).isEqualTo("auth.forbidden");
    }

    @Test
    void platformAuditIsSeparateFromTenantAuditAndPartitionsAreCreatedOnDemand() throws Exception {
        String operator = operatorLogin();
        deliver();
        // 未来月份的事件写入时自动建该月分区
        Instant future = Instant.parse("2031-07-15T12:00:00Z");
        audits.store(new AuditEvent(
                UUID.randomUUID(),
                future,
                AuditRealm.PLATFORM,
                null,
                SUPER_ADMIN,
                AuditCategory.OPERATION,
                "tenant.suspend",
                "tenant",
                TENANT_B.toString(),
                AuditOutcome.FAILURE,
                "tenant.illegalTransition",
                Map.of(),
                "forge-platform",
                null));
        audits.store(new AuditEvent(
                UUID.randomUUID(),
                future,
                AuditRealm.TENANT,
                TENANT_A,
                IDENTITIES.get(ADMIN_A),
                AuditCategory.OPERATION,
                "order.create",
                "order",
                null,
                AuditOutcome.SUCCESS,
                null,
                Map.of(),
                "demo-service",
                null));

        List<?> platform = list(operatorCall(operator, "/platform/audit/events"));
        assertThat(platform)
                .extracting(event -> object(event).get("action"))
                .containsExactly("tenant.suspend", "auth.login")
                .doesNotContain("order.create");
        Map<String, Object> suspend = object(platform.get(0));
        assertThat(suspend)
                .containsEntry("actorAccount", "operator")
                .containsEntry("outcome", "failure")
                .containsEntry("errorCode", "tenant.illegalTransition");
        assertThat(list(operatorCall(
                        operator, "/platform/audit/events?from=2031-07-01T00:00:00Z&to=2031-08-01T00:00:00Z")))
                .hasSize(1);
        assertThat(events(ADMIN_A, "?from=2031-07-01T00:00:00Z"))
                .extracting(event -> event.get("action"))
                .containsExactly("order.create");

        // Tenant Realm Token 不能访问平台审计，Platform Realm Token 也不能访问租户审计
        assertThat(status(call(ADMIN_A, "GET", "/platform/audit/events", null))).isEqualTo(401);
        assertThat(status(RestClient.create(baseUrl())
                        .get()
                        .uri("/audit/events")
                        .headers(headers -> headers.setBearerAuth(operator))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {})
                        .toEntity(Map.class)))
                .isEqualTo(401);
    }

    /** 读出 Outbox 中尚未写入的 Audit Event 并按消费者的逻辑写入审计表；重复写入同一事件被忽略。 */
    private List<AuditEvent> deliver() {
        List<AuditEvent> events = outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .toList();
        events.forEach(audits::store);
        return events;
    }

    private static AuditEvent single(List<AuditEvent> events, String action, AuditOutcome outcome, AuditRealm realm) {
        List<AuditEvent> matched = events.stream()
                .filter(event -> event.action().equals(action) && event.outcome() == outcome && event.realm() == realm)
                .toList();
        assertThat(matched).hasSize(1);
        return matched.get(0);
    }

    private List<Map<String, Object>> events(UUID membership, String query) throws Exception {
        return list(call(membership, "GET", "/audit/events" + query, null)).stream()
                .map(AuditTest::object)
                .toList();
    }

    private ResponseEntity<Map> operatorCall(String token, String uri) {
        return RestClient.create(baseUrl())
                .get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private static int status(ResponseEntity<Map> response) {
        return response.getStatusCode().value();
    }

    private static Object code(ResponseEntity<Map> response) {
        return response.getBody().get("code");
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(code(response)).isEqualTo("ok");
        return object(response.getBody().get("data"));
    }

    private static List<?> list(ResponseEntity<Map> response) {
        assertThat(code(response)).isEqualTo("ok");
        return (List<?>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private ResponseEntity<Map> post(String uri, Map<String, ?> body) {
        return RestClient.create(baseUrl())
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private ResponseEntity<Map> call(UUID membership, String method, String uri, Object body) throws Exception {
        String token = token(membership);
        var request = RestClient.create(baseUrl())
                .method(HttpMethod.valueOf(method))
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录流程；版本比对由网关负责，见 GatewaySecurityTest。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(IDENTITIES.get(membership).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", TENANTS.get(membership).toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private RestClient noRedirectClient() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }

    // 走完整的登录、授权码与换发流程，产出真实的登录成功事件；身份只有一个有效 Membership，自动进入。
    private void signIn(String account) throws Exception {
        String cookie = loginCookie("/auth/login", account, PASSWORD);
        String code = authorize(cookie, "tenant-app", "http://localhost:5174/", "tenant");
        post("/auth/exchange", Map.of("code", code, "codeVerifier", VERIFIER));
    }

    private String operatorLogin() throws Exception {
        String cookie = loginCookie("/platform/auth/login", "operator", OPERATOR_PASSWORD);
        String code = authorize(cookie, "platform-console", "http://localhost:5173/", "platform");
        return (String) data(post("/platform/auth/exchange", Map.of("code", code, "codeVerifier", VERIFIER)))
                .get("accessToken");
    }

    private String loginCookie(String uri, String account, String password) {
        var login = noRedirectClient()
                .post()
                .uri(uri)
                .body(Map.of("account", account, "password", password))
                .retrieve()
                .toEntity(Map.class);
        return login.getHeaders().getFirst("Set-Cookie").split(";")[0];
    }

    private String authorize(String cookie, String clientId, String redirectUri, String scope) throws Exception {
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var authorized = noRedirectClient()
                .get()
                .uri(builder -> builder.path("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", redirectUri)
                        .queryParam("scope", scope)
                        .queryParam("state", "test-state")
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("code_challenge", challenge)
                        .build())
                .header("Cookie", cookie)
                .retrieve()
                .toBodilessEntity();
        return UriComponentsBuilder.fromUri(authorized.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
    }
}
