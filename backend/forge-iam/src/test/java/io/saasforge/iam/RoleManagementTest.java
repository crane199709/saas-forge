package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import io.saasforge.sdk.core.api.ForgeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** 自定义角色、权限码授权、成员的角色分配与按业务服务模板预置的角色（工单 16），以及角色上的 Data Policy（工单 18）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/roles.sql")
class RoleManagementTest {
    static final String KEY_FILE = "target/test-roles-signing.jwk";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000000c1");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000c3");
    static final UUID MEMBER = UUID.fromString("019a0110-0000-7000-8000-0000000000c5");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-0000000000c7");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN, UUID.fromString("019a0110-0000-7000-8000-0000000000c2"),
            MEMBER, UUID.fromString("019a0110-0000-7000-8000-0000000000c4"),
            ADMIN_B, UUID.fromString("019a0110-0000-7000-8000-0000000000c6"));

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
    }

    @LocalServerPort
    int port;

    @Autowired
    PermissionService permissions;

    @Autowired
    RoleService roles;

    @Autowired
    StringRedisTemplate redisTemplate;

    // 各测试共用同一份清单内容；按测试名区分版本，重复上报相同内容为无操作。
    @BeforeEach
    void reportManifest() {
        permissions.report("demo-service", manifest("roles-1", List.of("order:read", "order:approve")));
    }

    @Test
    void revokedPermissionIsDeniedOnTheNextRequestAndItsMenuDisappears() throws Exception {
        String approver = createRole("approver", "审批员", List.of("order:approve", "iam:org:manage"));
        String reader = createRole("reader", "查看员", List.of("order:read"));

        long before = version(MEMBER);
        assertThat(assign(ADMIN, MEMBER, List.of(approver, reader))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(version(MEMBER)).isGreaterThan(before);
        // 多个角色的权限取并集，发布到 Redis 供业务服务的 @RequiresPermission 读取。
        assertThat(grants(MEMBER).roles()).containsExactlyInAnyOrder("approver", "reader");
        assertThat(grants(MEMBER).permissions())
                .containsExactlyInAnyOrder("order:approve", "order:read", "iam:org:manage");
        assertThat(menuCodes(MEMBER)).contains("orders.list", "orders.approve");
        assertThat(call(MEMBER, HttpMethod.GET, "/org/positions", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // 撤销授权：持有者的版本递增，旧 Token 被网关拒绝；新版本明细、菜单与 iam 自身的校验同步收回。
        before = version(MEMBER);
        var revoke =
                call(ADMIN, HttpMethod.PUT, "/roles/" + approver + "/permissions", Map.of("permissions", List.of()));
        assertThat(revoke.getStatusCode().value()).isEqualTo(200);
        assertThat(version(MEMBER)).isGreaterThan(before);
        assertThat(grants(MEMBER).permissions()).containsExactly("order:read");
        assertThat(menuCodes(MEMBER)).contains("orders.list").doesNotContain("orders.approve");
        assertThat(call(MEMBER, HttpMethod.GET, "/org/positions", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);

        // 取消角色分配同样即时生效。
        assign(ADMIN, MEMBER, List.of());
        assertThat(grants(MEMBER).permissions()).isEmpty();
        assertThat(menuCodes(MEMBER)).doesNotContain("orders.list");
    }

    @Test
    void builtinRolesAreProtectedAndRetiredCodesCannotBeGranted() throws Exception {
        Map<String, String> ids = roleIds(ADMIN);
        String owner = ids.get("owner");
        String admin = ids.get("tenant-admin");
        for (String builtin : List.of(owner, admin)) {
            assertThat(code(call(ADMIN, HttpMethod.DELETE, "/roles/" + builtin, null)))
                    .isEqualTo("role.builtin");
            assertThat(code(call(ADMIN, HttpMethod.PUT, "/roles/" + builtin, Map.of("name", "改名"))))
                    .isEqualTo("role.builtin");
            assertThat(code(call(
                            ADMIN,
                            HttpMethod.PUT,
                            "/roles/" + builtin + "/permissions",
                            Map.of("permissions", List.of("order:read")))))
                    .isEqualTo("role.builtin");
        }
        // 保留编码与已有编码都不能再用。
        assertThat(code(call(ADMIN, HttpMethod.POST, "/roles", Map.of("code", "owner", "name", "冒名"))))
                .isEqualTo("role.codeTaken");
        String auditor = createRole("auditor", "审计员", List.of("order:read"));
        assertThat(code(call(ADMIN, HttpMethod.POST, "/roles", Map.of("code", "auditor", "name", "重复"))))
                .isEqualTo("role.codeTaken");

        // Owner 只能经转让变更，不在角色分配范围内；Tenant Admin 可以分配。
        assertThat(code(assign(ADMIN, MEMBER, List.of(owner)))).isEqualTo("role.invalidAssignment");
        assertThat(assign(ADMIN, MEMBER, List.of(admin, auditor))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(grants(MEMBER).permissions()).contains("order:approve", "iam:role:manage");

        // 仍有成员持有的角色不能删除。
        assertThat(code(call(ADMIN, HttpMethod.DELETE, "/roles/" + auditor, null)))
                .isEqualTo("role.inUse");
        assign(ADMIN, MEMBER, List.of());

        // 下线的权限码从可选列表中消失，也不能再授予；已有授权保留，权限码恢复后随之恢复。
        permissions.report("demo-service", manifest("roles-retired", List.of("order:approve")));
        assertThat(grantableCodes(ADMIN)).doesNotContain("order:read").contains("order:approve", "iam:role:manage");
        assertThat(code(call(
                        ADMIN,
                        HttpMethod.PUT,
                        "/roles/" + auditor + "/permissions",
                        Map.of("permissions", List.of("order:read")))))
                .isEqualTo("role.invalidPermission");
        assertThat(permissionsOf(ADMIN, "auditor")).isEmpty();
        permissions.report("demo-service", manifest("roles-restored", List.of("order:read", "order:approve")));
        assertThat(permissionsOf(ADMIN, "auditor")).containsExactly("order:read");

        assertThat(call(ADMIN, HttpMethod.DELETE, "/roles/" + auditor, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(roleIds(ADMIN)).doesNotContainKey("auditor");
    }

    @Test
    void roleTemplatesFromBusinessServicesAreProvisionedAndEditable() throws Exception {
        permissions.report(
                "template-service",
                new ResourceManifest(
                        "template-service",
                        "1",
                        List.of(new Permission("report:view", "查看报表", "报表")),
                        List.of(),
                        List.of(new RoleTemplate("report-viewer", "报表查看员", List.of("report:view")))));
        roles.provisionTemplateRoles(TENANT_B);
        roles.provisionTemplateRoles(TENANT_B);

        assertThat(permissionsOf(ADMIN_B, "report-viewer")).containsExactly("report:view");
        String template = roleIds(ADMIN_B).get("report-viewer");
        assertThat(call(ADMIN_B, HttpMethod.PUT, "/roles/" + template, Map.of("name", "报表与订单"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        call(
                ADMIN_B,
                HttpMethod.PUT,
                "/roles/" + template + "/permissions",
                Map.of("permissions", List.of("report:view", "order:read")));
        assertThat(permissionsOf(ADMIN_B, "report-viewer")).containsExactly("order:read", "report:view");
    }

    @Test
    void dataScopesOnRolesArePublishedPerDataResourceAndUnionedAcrossRoles() throws Exception {
        permissions.report("ledger-service", ledgerManifest("ledger-service", "1"));
        var resources = (List<Object>) call(ADMIN, HttpMethod.GET, "/roles/data-resources", null)
                .getBody()
                .get("data");
        assertThat(resources)
                .contains(
                        Map.of(
                                "code", "ledger",
                                "name", "台账",
                                "service", "ledger-service",
                                "scopes", List.of("self", "department", "department_and_below", "all")),
                        // 只声明了归属成员的资源不能使用按部门的预设
                        Map.of(
                                "code",
                                "memo",
                                "name",
                                "备忘",
                                "service",
                                "ledger-service",
                                "scopes",
                                List.of("self", "all")));

        String own = createRole("ledger-own", "本人台账", List.of());
        String department = createRole("ledger-department", "本部门台账", List.of());
        assertThat(dataPolicies(own, List.of(Map.of("resource", "ledger", "scope", "self")))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        dataPolicies(department, List.of(Map.of("resource", "ledger", "scope", "department")));
        assign(ADMIN, MEMBER, List.of(own, department));

        // 多个角色在同一资源上的策略都发布出去，由业务服务取并集；没有策略的资源（memo）不出现，即默认拒绝。
        assertThat(grants(MEMBER).dataPolicies())
                .isEqualTo(Map.of("ledger", List.of(DataScope.DEPARTMENT.template(), DataScope.SELF.template())));
        assertThat(roleById(ADMIN, department).get("dataPolicies"))
                .isEqualTo(List.of(Map.of("resource", "ledger", "scope", "department")));

        // 策略变更即时生效：持有者版本递增，新版本明细只剩另一个角色的策略。
        long before = version(MEMBER);
        dataPolicies(own, List.of());
        assertThat(version(MEMBER)).isGreaterThan(before);
        assertThat(grants(MEMBER).dataPolicies()).isEqualTo(Map.of("ledger", List.of(DataScope.DEPARTMENT.template())));

        // 预置角色对全部数据资源拥有「全部」范围。
        assertThat(roles.grantsOf(ADMIN).dataPolicies())
                .containsEntry("ledger", List.of(DataScope.ALL.template()))
                .containsEntry("memo", List.of(DataScope.ALL.template()));

        // 资源不支持的预设、未声明的资源、未知的范围与预置角色都被拒绝。
        assertThat(code(dataPolicies(own, List.of(Map.of("resource", "memo", "scope", "department")))))
                .isEqualTo("role.invalidDataPolicy");
        assertThat(code(dataPolicies(own, List.of(Map.of("resource", "missing", "scope", "all")))))
                .isEqualTo("role.invalidDataPolicy");
        assertThat(code(dataPolicies(own, List.of(Map.of("resource", "ledger", "scope", "everything")))))
                .isEqualTo("role.invalidDataPolicy");
        assertThat(code(dataPolicies(roleIds(ADMIN).get("tenant-admin"), List.of())))
                .isEqualTo("role.builtin");

        // 下线的资源不在替换范围内：其上的策略保留，资源重新声明后随之恢复。
        dataPolicies(own, List.of(Map.of("resource", "memo", "scope", "self")));
        permissions.report("ledger-service", ledgerManifest("ledger-service", "2", false));
        assertThat(dataPolicies(own, List.of(Map.of("resource", "ledger", "scope", "all")))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        permissions.report("ledger-service", ledgerManifest("ledger-service", "3", true));
        assertThat(roleById(ADMIN, own).get("dataPolicies"))
                .isEqualTo(List.of(
                        Map.of("resource", "ledger", "scope", "all"), Map.of("resource", "memo", "scope", "self")));
        dataPolicies(own, List.of());

        // Data Resource 编码全平台唯一。
        assertThatThrownBy(() -> permissions.report("other-service", ledgerManifest("other-service", "1")))
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("manifest.dataResourceConflict"));
        assign(ADMIN, MEMBER, List.of());
    }

    @Test
    void rolesAreIsolatedPerTenantAndManagedOnlyWithThePermission() throws Exception {
        String local = createRole("local", "仅租户 A", List.of());

        assertThat(roleIds(ADMIN_B)).doesNotContainKey("local");
        assertThat(code(call(ADMIN_B, HttpMethod.PUT, "/roles/" + local, Map.of("name", "越权"))))
                .isEqualTo("role.notFound");
        assertThat(code(assign(ADMIN_B, ADMIN_B, List.of(local)))).isEqualTo("role.invalidAssignment");
        assertThat(code(assign(ADMIN_B, MEMBER, List.of()))).isEqualTo("role.membershipNotFound");

        // 没有 iam:role:manage 的成员不能管理角色；授予后即可。
        assertThat(call(MEMBER, HttpMethod.GET, "/roles", null).getStatusCode().value())
                .isEqualTo(403);
        String manager = createRole("role-manager", "角色管理员", List.of("iam:role:manage"));
        assign(ADMIN, MEMBER, List.of(manager));
        assertThat(call(MEMBER, HttpMethod.GET, "/roles", null).getStatusCode().value())
                .isEqualTo(200);
        assign(ADMIN, MEMBER, List.of());

        var members = call(ADMIN_B, HttpMethod.GET, "/roles/memberships?page=1&size=100", null);
        List<?> rows = (List<?>) members.getBody().get("data");
        assertThat(rows).hasSize(1);
        assertThat(((Map<?, ?>) rows.get(0)).get("membershipId")).isEqualTo(ADMIN_B.toString());
        assertThat((List<?>) ((Map<?, ?>) rows.get(0)).get("roleIds")).hasSize(1);
    }

    private static ResourceManifest manifest(String version, List<String> codes) {
        return new ResourceManifest(
                "demo-service",
                version,
                codes.stream().map(code -> new Permission(code, code, "订单")).toList(),
                List.of(new MenuNode(
                        "orders",
                        "订单",
                        null,
                        null,
                        null,
                        null,
                        // 菜单只能引用清单中声明的权限码，下线的编码连同其菜单一起去掉。
                        Stream.of(
                                        new MenuNode("orders.list", "订单列表", null, "demo", "orders", "order:read", null),
                                        new MenuNode(
                                                "orders.approve",
                                                "审批",
                                                null,
                                                "demo",
                                                "approvals",
                                                "order:approve",
                                                null))
                                .filter(node -> codes.contains(node.permission()))
                                .toList())));
    }

    private static ResourceManifest ledgerManifest(String service, String version) {
        return ledgerManifest(service, version, true);
    }

    private static ResourceManifest ledgerManifest(String service, String version, boolean withMemo) {
        return new ResourceManifest(
                service,
                version,
                List.of(),
                List.of(),
                List.of(),
                Stream.of(
                                new DataResource(
                                        "ledger",
                                        "台账",
                                        "ledgers",
                                        List.of(
                                                new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"),
                                                new DataAttribute(
                                                        "departmentPath", "归属部门", "ltree", "department_path"))),
                                new DataResource(
                                        "memo",
                                        "备忘",
                                        "memos",
                                        List.of(new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"))))
                        .filter(resource -> withMemo || !resource.code().equals("memo"))
                        .toList());
    }

    private ResponseEntity<Map> dataPolicies(String roleId, List<Map<String, String>> policies) throws Exception {
        return call(ADMIN, HttpMethod.PUT, "/roles/" + roleId + "/data-policies", Map.of("policies", policies));
    }

    private Map<?, ?> roleById(UUID admin, String id) throws Exception {
        for (Object item :
                (List<?>) call(admin, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            if (id.equals(((Map<?, ?>) item).get("id"))) return (Map<?, ?>) item;
        }
        throw new AssertionError("Role not found: " + id);
    }

    private String createRole(String code, String name, List<String> codes) throws Exception {
        var response = call(ADMIN, HttpMethod.POST, "/roles", Map.of("code", code, "name", name, "permissions", codes));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) ((Map<?, ?>) response.getBody().get("data")).get("id");
    }

    private ResponseEntity<Map> assign(UUID admin, UUID membership, List<String> roleIds) throws Exception {
        return call(admin, HttpMethod.PUT, "/roles/memberships/" + membership, Map.of("roleIds", roleIds));
    }

    private Map<String, String> roleIds(UUID admin) throws Exception {
        var ids = new HashMap<String, String>();
        for (Object item :
                (List<?>) call(admin, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            var role = (Map<?, ?>) item;
            ids.put((String) role.get("code"), (String) role.get("id"));
        }
        return ids;
    }

    private List<Object> permissionsOf(UUID admin, String roleCode) throws Exception {
        for (Object item :
                (List<?>) call(admin, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            var role = (Map<?, ?>) item;
            if (roleCode.equals(role.get("code"))) return List.copyOf((List<?>) role.get("permissions"));
        }
        throw new AssertionError("Role not found: " + roleCode);
    }

    private List<Object> grantableCodes(UUID admin) throws Exception {
        return ((List<?>) call(admin, HttpMethod.GET, "/roles/permissions", null)
                        .getBody()
                        .get("data"))
                .stream().<Object>map(item -> ((Map<?, ?>) item).get("code")).toList();
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
                .claim("tenant_id", (membership.equals(ADMIN_B) ? TENANT_B : TENANT).toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
