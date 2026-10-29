package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** 套餐限定可授予与生效的权限码、降级失效与升级恢复、成员数配额，以及供 platform 选择的权限码目录（工单 20）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plans.sql")
class PlanEnforcementTest {
    static final String KEY_FILE = "target/test-plans-signing.jwk";
    static final UUID TENANT = UUID.fromString("019a0120-0000-7000-8000-0000000000a0");
    static final UUID ADMIN = UUID.fromString("019a0120-0000-7000-8000-0000000000a2");
    static final UUID MEMBER = UUID.fromString("019a0120-0000-7000-8000-0000000000a4");
    static final UUID QUOTA_TENANT = UUID.fromString("019a0120-0000-7000-8000-0000000000a6");
    static final UUID QUOTA_ADMIN = UUID.fromString("019a0120-0000-7000-8000-0000000000a8");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN, UUID.fromString("019a0120-0000-7000-8000-0000000000a1"),
            MEMBER, UUID.fromString("019a0120-0000-7000-8000-0000000000a3"),
            QUOTA_ADMIN, UUID.fromString("019a0120-0000-7000-8000-0000000000a7"));
    static final UUID BASIC = UUID.fromString("019a0120-0000-7000-8000-0000000000b1");
    static final UUID PRO = UUID.fromString("019a0120-0000-7000-8000-0000000000b2");

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
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
        registry.add("forge.iam.manifest.tokens.forge-platform", () -> "test-platform-token");
    }

    @LocalServerPort
    int port;

    @Autowired
    PermissionService permissions;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void reportManifest() {
        permissions.report(
                "demo-service",
                new ResourceManifest(
                        "demo-service",
                        "plans-1",
                        List.of(
                                new Permission("order:read", "查看订单", "订单"),
                                new Permission("order:approve", "审批订单", "订单")),
                        List.of(new MenuNode(
                                "orders",
                                "订单",
                                null,
                                null,
                                null,
                                null,
                                List.of(
                                        new MenuNode("orders.list", "订单列表", null, "demo", "orders", "order:read", null),
                                        new MenuNode(
                                                "orders.approve",
                                                "审批",
                                                null,
                                                "demo",
                                                "approvals",
                                                "order:approve",
                                                null))))));
    }

    @Test
    void downgradeDisablesGrantsOutsideThePlanAndUpgradeRestoresThem() throws Exception {
        var created = call(
                ADMIN,
                HttpMethod.POST,
                "/roles",
                Map.of("code", "approver", "name", "审批员", "permissions", List.of("order:read", "order:approve")));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String approver = (String) data(created).get("id");
        assertThat(call(ADMIN, HttpMethod.PUT, "/roles/memberships/" + MEMBER, Map.of("roleIds", List.of(approver)))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        // 未登记 Plan 的租户不受套餐限制
        assertThat(grants(MEMBER).permissions()).containsExactlyInAnyOrder("order:read", "order:approve");

        // 降级：成员的版本递增，旧 Token 被网关拒绝；新版本的明细（业务服务 @RequiresPermission 读取的内容）与菜单不再含超出的权限码
        long before = version(MEMBER);
        deliver(planChanged(BASIC, List.of("order:read"), null, 1));

        assertThat(version(MEMBER)).isGreaterThan(before);
        assertThat(grants(MEMBER).permissions()).containsExactly("order:read");
        assertThat(menuCodes(MEMBER)).contains("orders.list").doesNotContain("orders.approve");
        // Tenant Admin 拥有 Plan 内的全部权限，iam 自身的管理权限码不受套餐限制
        assertThat(grants(ADMIN).permissions())
                .contains("order:read", "iam:role:manage", "iam:member:manage", "iam:org:manage")
                .doesNotContain("order:approve");

        // 授权界面只展示 Plan 内的权限码，越界授权被拒绝
        assertThat(grantableCodes()).contains("order:read", "iam:role:manage").doesNotContain("order:approve");
        assertThat(permissionsOf(approver)).containsExactly("order:read");
        assertThat(code(call(
                        ADMIN,
                        HttpMethod.POST,
                        "/roles",
                        Map.of("code", "approver2", "name", "审批员 2", "permissions", List.of("order:approve")))))
                .isEqualTo("role.permissionNotInPlan");
        assertThat(code(call(
                        ADMIN,
                        HttpMethod.PUT,
                        "/roles/" + approver + "/permissions",
                        Map.of("permissions", List.of("order:read", "order:approve")))))
                .isEqualTo("role.permissionNotInPlan");
        // 在 Plan 内整体替换授权不会撤销超出 Plan 的记录
        assertThat(call(
                                ADMIN,
                                HttpMethod.PUT,
                                "/roles/" + approver + "/permissions",
                                Map.of("permissions", List.of("order:read")))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // 乱序或重复送达的旧版本不覆盖已应用的 Plan，也不递增成员版本
        long applied = version(MEMBER);
        deliver(planChanged(PRO, List.of("order:read", "order:approve"), null, 1));
        assertThat(version(MEMBER)).isEqualTo(applied);
        assertThat(grants(MEMBER).permissions()).containsExactly("order:read");

        // 升级：保留的授权随之恢复
        deliver(planChanged(PRO, List.of("order:read", "order:approve"), null, 2));

        assertThat(version(MEMBER)).isGreaterThan(applied);
        assertThat(grants(MEMBER).permissions()).containsExactlyInAnyOrder("order:read", "order:approve");
        assertThat(menuCodes(MEMBER)).contains("orders.list", "orders.approve");
        assertThat(permissionsOf(approver)).containsExactlyInAnyOrder("order:read", "order:approve");
    }

    @Test
    void invitationBeyondTheMemberQuotaIsRejected() throws Exception {
        // 已有管理员与成员两人，上限三人
        deliver(new TenantPlanChanged(QUOTA_TENANT, BASIC, "basic", List.of(), List.of(), 3, null, 1));

        var first =
                call(QUOTA_ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "quota-1@example.com"));
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        var second =
                call(QUOTA_ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "quota-2@example.com"));
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("member.quotaExceeded");
        // 邀请中的成员计入人数；重发已有的邀请不受影响
        assertThat(call(QUOTA_ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "quota-1@example.com"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);

        // 撤回邀请腾出名额
        String invited = (String) data(first).get("membershipId");
        assertThat(call(QUOTA_ADMIN, HttpMethod.DELETE, "/members/" + invited, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(QUOTA_ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "quota-2@example.com"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
    }

    @Test
    void platformReadsThePermissionCatalogWithItsServiceToken() {
        var catalog = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/internal/permissions")
                .headers(headers -> headers.setBearerAuth("test-platform-token"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(catalog.getStatusCode().value()).isEqualTo(200);
        List<Object> codes = ((List<?>) catalog.getBody().get("data"))
                .stream().<Object>map(item -> ((Map<?, ?>) item).get("code")).toList();
        // iam 自身的管理权限码不受套餐限制，不在目录中
        assertThat(codes).contains("order:read", "order:approve").noneMatch(code -> ((String) code).startsWith("iam:"));

        var anonymous = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/internal/permissions")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toBodilessEntity();
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
    }

    private static TenantPlanChanged planChanged(UUID planId, List<String> codes, Integer memberQuota, long version) {
        return new TenantPlanChanged(
                TENANT, planId, planId.equals(BASIC) ? "basic" : "pro", codes, List.of(), memberQuota, null, version);
    }

    /** 与 SDK 消费端一致：先恢复发布方的租户，再在事务中调用处理器。 */
    @SuppressWarnings("unchecked")
    private void deliver(TenantPlanChanged event) {
        var handler = (EventHandler<TenantPlanChanged>) handlers.stream()
                .filter(candidate -> candidate.eventType() == TenantPlanChanged.class)
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(event.tenantId(), () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    private long version(UUID membership) {
        String value = redisTemplate.opsForValue().get(MembershipStateKeys.version(TENANT, membership));
        return value == null ? 1 : Long.parseLong(value);
    }

    // 按业务服务的方式读取：SDK 的明细模型直接反序列化 iam 发布的 JSON。
    private MembershipGrants grants(UUID membership) {
        String value =
                redisTemplate.opsForValue().get(MembershipStateKeys.grants(TENANT, membership, version(membership)));
        return JsonMapper.builder().build().readValue(value, MembershipGrants.class);
    }

    private List<Object> grantableCodes() throws Exception {
        return ((List<?>) call(ADMIN, HttpMethod.GET, "/roles/permissions", null)
                        .getBody()
                        .get("data"))
                .stream().<Object>map(item -> ((Map<?, ?>) item).get("code")).toList();
    }

    private List<Object> permissionsOf(String roleId) throws Exception {
        for (Object item :
                (List<?>) call(ADMIN, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            var role = (Map<?, ?>) item;
            if (roleId.equals(role.get("id"))) return List.copyOf((List<?>) role.get("permissions"));
        }
        throw new AssertionError("Role not found: " + roleId);
    }

    private List<String> menuCodes(UUID membership) throws Exception {
        var access = (Map<?, ?>)
                call(membership, HttpMethod.GET, "/auth/access", null).getBody().get("data");
        List<String> codes = new ArrayList<>();
        collect((List<?>) access.get("menus"), codes);
        return codes;
    }

    private static void collect(List<?> nodes, List<String> codes) {
        for (Object node : nodes) {
            codes.add((String) ((Map<?, ?>) node).get("code"));
            Object children = ((Map<?, ?>) node).get("children");
            if (children != null) collect((List<?>) children, codes);
        }
    }

    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private static Object code(ResponseEntity<Map> response) {
        return response.getBody().get("code");
    }

    private ResponseEntity<Map> call(UUID membership, HttpMethod method, String uri, Object body) throws Exception {
        String token = token(membership);
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录与授权码流程；版本比对由网关负责，见 GatewaySecurityTest。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(IDENTITIES.get(membership).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", (membership.equals(QUOTA_ADMIN) ? QUOTA_TENANT : TENANT).toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
