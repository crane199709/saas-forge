package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.audit.service.AuditService;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.abac.DataResourceDefinition;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.audit.AuditEvent;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.json.JsonMapper;

/** 自定义角色、权限码授权、成员的角色分配与按业务服务模板预置的角色（工单 16），以及角色上的 Data Policy（工单 18、19）。 */
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

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
    }

    static final Map<String, Object> OWNER_ATTRIBUTE =
            Map.of("name", "resource.ownerId", "label", "归属成员", "type", "uuid", "multiValued", false);
    static final Map<String, Object> DEPARTMENT_ATTRIBUTE =
            Map.of("name", "resource.departmentPath", "label", "归属部门", "type", "ltree", "multiValued", false);
    static final Map<String, Object> AMOUNT_ATTRIBUTE =
            Map.of("name", "resource.amount", "label", "金额", "type", "number", "multiValued", false);

    @LocalServerPort
    int port;

    @Autowired
    PermissionService permissions;

    @Autowired
    RoleService roles;

    @Autowired
    AuditService audits;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    StringRedisTemplate redisTemplate;

    // 各测试共用同一份清单内容；按测试名区分版本，重复上报相同内容为无操作。
    @BeforeEach
    void reportManifest() {
        permissions.report("demo-service", manifest("roles-1", List.of("order:read", "order:approve")));
    }

    @Test
    void concurrentRestoreSucceedsOnlyOnceForEachIamResource() throws Exception {
        for (String path : List.of("/roles", "/org/departments", "/org/positions")) {
            String id = (String)
                    data(call(ADMIN, HttpMethod.POST, path, Map.of("code", "concurrent-restore", "name", "并发恢复")))
                            .get("id");
            call(ADMIN, HttpMethod.DELETE, path + "/" + id, null);
            var responses = race(
                    () -> call(ADMIN, HttpMethod.POST, path + "/" + id + "/restore", null),
                    () -> call(ADMIN, HttpMethod.POST, path + "/" + id + "/restore", null));
            assertThat(responses)
                    .extracting(response -> response.getStatusCode().value())
                    .containsExactlyInAnyOrder(200, 404);
            assertThat((List<Map<String, Object>>)
                            call(ADMIN, HttpMethod.GET, path, null).getBody().get("data"))
                    .filteredOn(row -> id.equals(row.get("id")))
                    .hasSize(1);
            assertThat((List<Map<String, Object>>) call(ADMIN, HttpMethod.GET, path + "?deleted=true", null)
                            .getBody()
                            .get("data"))
                    .noneMatch(row -> id.equals(row.get("id")));
        }
    }

    @Test
    void departmentRestoreSerializesWithParentDeletionAndMovement() throws Exception {
        String parent = (String) data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "竞争父部门")))
                .get("id");
        String child = (String)
                data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "竞争子部门", "parentId", parent)))
                        .get("id");
        call(ADMIN, HttpMethod.DELETE, "/org/departments/" + child, null);
        assertThat(race(
                        () -> call(ADMIN, HttpMethod.POST, "/org/departments/" + child + "/restore", null),
                        () -> call(ADMIN, HttpMethod.DELETE, "/org/departments/" + parent, null)))
                .extracting(response -> response.getStatusCode().value())
                .containsExactlyInAnyOrder(200, 409);
        var rows = (List<Map<String, Object>>)
                call(ADMIN, HttpMethod.GET, "/org/departments", null).getBody().get("data");
        assertThat(rows.stream().anyMatch(row -> child.equals(row.get("id"))))
                .isEqualTo(rows.stream().anyMatch(row -> parent.equals(row.get("id"))));

        // 另一棵树并发恢复下级和移动上级，两种锁顺序都必须得到当前父路径。
        String movingParent = (String) data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "移动父部门")))
                .get("id");
        String movingChild = (String) data(call(
                        ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "移动子部门", "parentId", movingParent)))
                .get("id");
        String destination = (String) data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "目标部门")))
                .get("id");
        call(ADMIN, HttpMethod.DELETE, "/org/departments/" + movingChild, null);
        assertThat(race(
                        () -> call(ADMIN, HttpMethod.POST, "/org/departments/" + movingChild + "/restore", null),
                        () -> call(
                                ADMIN,
                                HttpMethod.PUT,
                                "/org/departments/" + movingParent + "/parent",
                                Map.of("parentId", destination))))
                .allSatisfy(
                        response -> assertThat(response.getStatusCode().value()).isEqualTo(200));
        var moved = (List<Map<String, Object>>)
                call(ADMIN, HttpMethod.GET, "/org/departments", null).getBody().get("data");
        assertThat(moved)
                .filteredOn(row -> movingChild.equals(row.get("id")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry(
                                "path", label(destination) + "." + label(movingParent) + "." + label(movingChild)));
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> {
                start.await();
                return first.call();
            });
            var b = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restoreAuditsSuccessFailureAndTenantBoundariesForAllIamResources() throws Exception {
        for (String path : List.of("/roles", "/org/departments", "/org/positions")) {
            String type = path.equals("/roles") ? "role" : path.endsWith("departments") ? "department" : "position";
            String id =
                    (String) data(call(ADMIN, HttpMethod.POST, path, Map.of("code", "audit-restore", "name", "恢复审计")))
                            .get("id");
            call(ADMIN, HttpMethod.DELETE, path + "/" + id, null);
            assertThat(call(MEMBER, HttpMethod.GET, path + "?deleted=true", null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(403);
            assertThat(call(ADMIN_B, HttpMethod.GET, path + "?deleted=true", null)
                            .getBody()
                            .get("data"))
                    .isEqualTo(List.of());
            assertThat(call(MEMBER, HttpMethod.POST, path + "/" + id + "/restore", null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(403);
            assertThat(call(ADMIN_B, HttpMethod.POST, path + "/" + id + "/restore", null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(404);
            assertThat(call(ADMIN, HttpMethod.POST, path + "/" + id + "/restore", null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            assertThat(call(ADMIN, HttpMethod.POST, path + "/" + id + "/restore", null)
                            .getStatusCode()
                            .value())
                    .isEqualTo(404);
            // 沿用 AuditTest 的 MQ 投递替身；业务断言仍读取公开审计 HTTP 接口。
            outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                    .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                    .forEach(audits::store);
            var events = (List<Map<String, Object>>)
                    call(ADMIN, HttpMethod.GET, "/audit/events?action=" + type + ".restore&resourceId=" + id, null)
                            .getBody()
                            .get("data");
            assertThat(events).hasSize(3);
            assertThat(events)
                    .filteredOn(event -> "success".equals(event.get("outcome")))
                    .singleElement()
                    .satisfies(event -> assertThat(event)
                            .containsEntry("resourceType", type)
                            .containsEntry("actorId", IDENTITIES.get(ADMIN).toString())
                            .containsEntry("service", "forge-iam"));
            assertThat(events)
                    .filteredOn(event -> "failure".equals(event.get("outcome")))
                    .extracting(event -> event.get("errorCode"))
                    .contains("auth.forbidden");
            var foreign = (List<Map<String, Object>>)
                    call(ADMIN_B, HttpMethod.GET, "/audit/events?action=" + type + ".restore&resourceId=" + id, null)
                            .getBody()
                            .get("data");
            assertThat(foreign)
                    .singleElement()
                    .satisfies(event -> assertThat(event)
                            .containsEntry("outcome", "failure")
                            .containsEntry("actorId", IDENTITIES.get(ADMIN_B).toString()));
        }
    }

    @Test
    void roleRestoreReportsOnlySameTenantCodeConflicts() throws Exception {
        String id = createRole("restore-conflict", "原角色", List.of());
        call(ADMIN, HttpMethod.DELETE, "/roles/" + id, null);
        String replacement = createRole("restore-conflict", "替代角色", List.of());
        var rejected = call(ADMIN, HttpMethod.POST, "/roles/" + id + "/restore", null);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("resource.restoreConflict");
        call(ADMIN, HttpMethod.DELETE, "/roles/" + replacement, null);
        assertThat(call(ADMIN_B, HttpMethod.POST, "/roles", Map.of("code", "restore-conflict", "name", "乙角色"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        assertThat(call(ADMIN, HttpMethod.POST, "/roles/" + id + "/restore", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void deletedRoleCanBeListedAndRestoredWithItsConfigurationAndNoMembers() throws Exception {
        permissions.report("ledger-service", ledgerManifest("ledger-service", "restore"));
        String id = createRole("restore-role", "可恢复角色", List.of("order:read"));
        var policies = List.of(Map.of("resource", "ledger", "scope", "self"));
        assertThat(dataPolicies(id, policies).getStatusCode().value()).isEqualTo(200);
        assign(ADMIN, MEMBER, List.of(id));
        assertThat((List<Object>)
                        data(call(MEMBER, HttpMethod.GET, "/auth/access", null)).get("permissions"))
                .contains("order:read");
        assign(ADMIN, MEMBER, List.of());
        assertThat(call(ADMIN, HttpMethod.DELETE, "/roles/" + id, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(roleIds(ADMIN)).doesNotContainKey("restore-role");
        assertThat((List<Object>)
                        data(call(MEMBER, HttpMethod.GET, "/auth/access", null)).get("permissions"))
                .doesNotContain("order:read");
        var deleted = ((List<Map<String, Object>>) call(ADMIN, HttpMethod.GET, "/roles?deleted=true", null)
                        .getBody()
                        .get("data"))
                .stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
        assertThat(deleted).containsEntry("updatedBy", IDENTITIES.get(ADMIN).toString());
        assertThat(deleted.get("updatedAt")).isNotNull();
        assertThat(call(ADMIN, HttpMethod.POST, "/roles/" + id + "/restore", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var restored = roleById(ADMIN, id);
        assertThat(restored.get("permissions")).isEqualTo(List.of("order:read"));
        assertThat(restored.get("dataPolicies")).isEqualTo(policies);
        assertThat(restored.get("memberCount")).isEqualTo(0);
        assertThat(Instant.parse((String) restored.get("updatedAt")))
                .isAfter(Instant.parse((String) deleted.get("updatedAt")));
        assertThat((List<Object>)
                        data(call(MEMBER, HttpMethod.GET, "/auth/access", null)).get("permissions"))
                .doesNotContain("order:read");
        assertThat((List<Map<String, Object>>) call(ADMIN, HttpMethod.GET, "/roles/memberships?size=100", null)
                        .getBody()
                        .get("data"))
                .filteredOn(row -> MEMBER.toString().equals(row.get("membershipId")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("roleIds")).isEqualTo(List.of()));
    }

    @Test
    @Sql(scripts = {"/fixtures/roles.sql", "/fixtures/permission-tree-plan.sql"})
    @Sql(scripts = "/fixtures/permission-tree-plan-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void permissionTreePrunesPlanPermissionsSubAppsAndEmptyGroups() throws Exception {
        permissions.report(
                "plan-tree-service",
                new ResourceManifest(
                        "plan-tree-service",
                        "1",
                        List.of(
                                new Permission("pt:read", "查看", "树"), new Permission("pt:edit", "编辑", "树"),
                                new Permission("pt:outside", "套餐外", "树"), new Permission("pt:orphan", "隐藏页面操作", "树")),
                        List.of(new MenuNode(
                                "pt",
                                "树",
                                null,
                                null,
                                null,
                                null,
                                List.of(
                                        new MenuNode(
                                                "pt.visible",
                                                "可见页",
                                                null,
                                                "demo",
                                                "visible",
                                                "pt:read",
                                                List.of(),
                                                List.of("pt:edit", "pt:outside")),
                                        new MenuNode("pt.public", "公共页", null, "demo", "public", null, List.of()),
                                        new MenuNode(
                                                "pt.other-app",
                                                "其他应用",
                                                null,
                                                "outside",
                                                "other",
                                                "pt:read",
                                                List.of(),
                                                List.of("pt:orphan")),
                                        // 页面权限在套餐外时，套餐内的操作也随页面隐藏
                                        new MenuNode(
                                                "pt.blocked",
                                                "套餐外页面",
                                                null,
                                                "demo",
                                                "blocked",
                                                "pt:outside",
                                                List.of(),
                                                List.of("pt:read")),
                                        new MenuNode(
                                                "pt.empty",
                                                "空分组",
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of(new MenuNode(
                                                        "pt.outside",
                                                        "套餐外页面",
                                                        null,
                                                        "demo",
                                                        "outside",
                                                        "pt:outside",
                                                        List.of()))))))));
        var response = call(ADMIN_B, HttpMethod.GET, "/roles/permission-tree", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var tree = data(response);
        var menus = (List<Map<String, Object>>) tree.get("menus");
        assertThat(menus.stream().map(node -> node.get("code"))).containsExactly("pt");
        var children = (List<Map<String, Object>>) menus.get(0).get("children");
        assertThat(children.stream().map(node -> node.get("code"))).containsExactly("pt.visible", "pt.public");
        assertThat(children.get(0).get("permission"))
                .isEqualTo(Map.of("code", "pt:read", "name", "查看", "sharedWith", List.of()));
        assertThat(children.get(0).get("actions"))
                .isEqualTo(List.of(Map.of("code", "pt:edit", "name", "编辑", "sharedWith", List.of())));
        var others = (List<Map<String, Object>>) tree.get("others");
        assertThat(others.stream().map(item -> item.get("code")))
                .contains("pt:orphan", "iam:role:manage")
                .doesNotContain("pt:read", "pt:edit", "pt:outside");
        var flat = (List<Map<String, Object>>) call(ADMIN_B, HttpMethod.GET, "/roles/permissions", null)
                .getBody()
                .get("data");
        assertThat(others)
                .isEqualTo(flat.stream()
                        .filter(item -> !List.of("pt:read", "pt:edit").contains(item.get("code")))
                        .toList());
    }

    @Test
    void permissionTreeShowsSharedCodesAndKeepsUnplacedPermissions() throws Exception {
        permissions.report(
                "tree-service",
                new ResourceManifest(
                        "tree-service",
                        "1",
                        List.of(new Permission("tree:read", "查看", "树"), new Permission("tree:edit", "编辑", "树")),
                        List.of(new MenuNode(
                                "tree",
                                "树",
                                "grid",
                                null,
                                null,
                                null,
                                List.of(
                                        new MenuNode(
                                                "tree.first",
                                                "第一页",
                                                null,
                                                "demo",
                                                "first",
                                                "tree:read",
                                                List.of(),
                                                List.of("tree:edit")),
                                        new MenuNode(
                                                "tree.second", "第二页", null, "demo", "second", "tree:read", List.of()),
                                        new MenuNode(
                                                "tree.public",
                                                "公共页",
                                                null,
                                                "demo",
                                                "public",
                                                null,
                                                List.of(),
                                                List.of("tree:read")))))));
        var response = call(ADMIN, HttpMethod.GET, "/roles/permission-tree", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var tree = data(response);
        var menus = (List<Map<String, Object>>) tree.get("menus");
        var group = menus.stream()
                .filter(node -> node.get("code").equals("tree"))
                .findFirst()
                .orElseThrow();
        var children = (List<Map<String, Object>>) group.get("children");
        assertThat(children).hasSize(3);
        assertThat((Map<String, Object>) children.get(0).get("permission"))
                .containsEntry("code", "tree:read")
                .containsEntry("name", "查看")
                .containsEntry("sharedWith", List.of("tree.second", "tree.public"));
        assertThat(children.get(0).get("actions"))
                .isEqualTo(List.of(Map.of("code", "tree:edit", "name", "编辑", "sharedWith", List.of())));
        assertThat(children.get(2)).containsEntry("permission", null);
        var others = (List<Map<String, Object>>) tree.get("others");
        assertThat(others.stream().map(item -> item.get("code")))
                .contains("iam:role:manage", "iam:member:manage")
                .doesNotContain("tree:read", "tree:edit");
        assertThat(call(MEMBER, HttpMethod.GET, "/roles/permission-tree", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        String manager = createRole("tree-manager", "角色管理员", List.of("iam:role:manage"));
        assign(ADMIN, MEMBER, List.of(manager));
        assertThat(data(call(MEMBER, HttpMethod.GET, "/roles/permission-tree", null)))
                .isEqualTo(tree);
        assign(ADMIN, MEMBER, List.of());
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
                                "scopes", List.of("self", "department", "department_and_below", "all"),
                                "attributes", List.of(OWNER_ATTRIBUTE, DEPARTMENT_ATTRIBUTE, AMOUNT_ATTRIBUTE)),
                        // 只声明了归属成员的资源不能使用按部门的预设
                        Map.of(
                                "code",
                                "memo",
                                "name",
                                "备忘",
                                "service",
                                "ledger-service",
                                "scopes",
                                List.of("self", "all"),
                                "attributes",
                                List.of(OWNER_ATTRIBUTE)));

        String own = createRole("ledger-own", "本人台账", List.of());
        // 新建角色时一并设置数据范围，与授权在同一事务写入；非法的范围让整个新建失败，不留下没有范围的角色。
        assertThat(code(call(
                        ADMIN,
                        HttpMethod.POST,
                        "/roles",
                        Map.of(
                                "code",
                                "ledger-department",
                                "name",
                                "本部门台账",
                                "dataPolicies",
                                List.of(Map.of("resource", "memo", "scope", "department"))))))
                .isEqualTo("role.invalidDataPolicy");
        assertThat(roleIds(ADMIN)).doesNotContainKey("ledger-department");
        var created = call(
                ADMIN,
                HttpMethod.POST,
                "/roles",
                Map.of(
                        "code",
                        "ledger-department",
                        "name",
                        "本部门台账",
                        "dataPolicies",
                        List.of(Map.of("resource", "ledger", "scope", "department"))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(((Map<?, ?>) created.getBody().get("data")).get("dataPolicies"))
                .isEqualTo(List.of(Map.of("resource", "ledger", "scope", "department")));
        String department = (String) ((Map<?, ?>) created.getBody().get("data")).get("id");
        assertThat(dataPolicies(own, List.of(Map.of("resource", "ledger", "scope", "self")))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
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
    void customConditionsAreValidatedAgainstTheTenantCatalogPublishedAndPreviewed() throws Exception {
        permissions.report("ledger-service", ledgerManifest("ledger-service", "1"));
        String sales = (String) data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "销售")))
                .get("id");
        String east =
                (String) data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "华东", "parentId", sales)))
                        .get("id");
        assertThat(call(
                                ADMIN,
                                HttpMethod.POST,
                                "/org/extended-attributes",
                                Map.of("code", "vip", "name", "VIP", "type", "boolean", "required", false))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        var assignment = new HashMap<String, Object>();
        assignment.put("primaryDepartmentId", sales);
        assignment.put("departmentIds", List.of());
        assignment.put("positionIds", List.of());
        assignment.put("attributes", Map.of("vip", true));
        call(ADMIN, HttpMethod.PUT, "/org/memberships/" + MEMBER + "/assignment", assignment);

        // 编辑器的可选项：平台主体属性、本租户的扩展属性、环境属性与部门取值（完整名称）。
        var options = data(call(ADMIN, HttpMethod.GET, "/roles/condition-options", null));
        assertThat((List<Object>) options.get("subject"))
                .contains(
                        attribute("subject.departmentPaths", null, "ltree", true),
                        attribute("subject.attributes.vip", "VIP", "boolean", false));
        assertThat((List<Object>) options.get("environment"))
                .containsExactly(attribute("env.now", null, "number", false));
        String salesPath = label(sales);
        String eastPath = salesPath + "." + label(east);
        assertThat((List<Object>) options.get("departments"))
                .contains(Map.of("value", salesPath, "label", "销售"), Map.of("value", eastPath, "label", "销售 / 华东"));

        // 「金额大于 1000 且属于本部门及下级」，或 VIP 成员可见全部。
        Map<String, Object> condition = Map.of(
                "or",
                List.of(
                        Map.of(
                                "and",
                                List.of(
                                        Map.of("attr", "resource.amount", "op", "gt", "value", 1000),
                                        Map.of(
                                                "attr",
                                                "resource.departmentPath",
                                                "op",
                                                "under",
                                                "value",
                                                Map.of("ref", "subject.departmentPaths")))),
                        Map.of("attr", "subject.attributes.vip", "op", "eq", "value", true)));
        Map<String, Object> custom = Map.of("resource", "ledger", "scope", "custom", "condition", condition);

        // 预览：成员当前的主体属性对样例数据逐条求值，未给出的属性视为空。
        var preview = data(call(
                ADMIN,
                HttpMethod.POST,
                "/roles/data-policies/preview",
                Map.of(
                        "policy",
                        Map.of(
                                "resource",
                                "ledger",
                                "scope",
                                "custom",
                                "condition",
                                ((List<?>) condition.get("or")).get(0)),
                        "membershipId",
                        MEMBER,
                        "samples",
                        List.of(
                                Map.of("amount", 1500, "departmentPath", eastPath),
                                Map.of("amount", 500, "departmentPath", eastPath),
                                Map.of("amount", 1500, "departmentPath", "other"),
                                Map.of("departmentPath", salesPath)))));
        assertThat(preview.get("matches")).isEqualTo(List.of(true, false, false, false));
        assertThat((Map<String, Object>) preview.get("context"))
                .containsEntry("subject.membershipId", MEMBER.toString())
                .containsEntry("subject.departmentPaths", List.of(salesPath))
                .containsEntry("subject.attributes.vip", true)
                .containsKey("env.now");
        var badSample = call(
                ADMIN,
                HttpMethod.POST,
                "/roles/data-policies/preview",
                Map.of(
                        "policy", custom,
                        "membershipId", MEMBER,
                        "samples", List.of(Map.of("amount", "很多"))));
        assertThat(code(badSample)).isEqualTo("role.invalidPreviewSample");
        assertThat((String) badSample.getBody().get("userTip")).contains("第 1 条");

        // 保存并发布：条件原样随明细发布，SDK 按发布的扩展属性类型加载。
        String role = createRole("ledger-custom", "大额台账", List.of());
        assign(ADMIN, MEMBER, List.of(role));
        long before = version(MEMBER);
        assertThat(dataPolicies(role, List.of(custom)).getStatusCode().value()).isEqualTo(200);
        assertThat(version(MEMBER)).isGreaterThan(before);
        MembershipGrants published = grants(MEMBER);
        String json = published.dataPolicies().get("ledger").get(0);
        assertThat(JsonMapper.builder().build().readTree(json))
                .isEqualTo(JsonMapper.builder().build().valueToTree(condition));
        assertThat(published.subject().attributeTypes()).containsEntry("vip", "boolean");
        assertThat(DataResourceDefinition.of(ledgerManifest("ledger-service", "1")
                                .dataResources()
                                .get(0))
                        .parse(json, published.subject().attributeTypes()))
                .isNotNull();
        assertThat(roleById(ADMIN, role).get("dataPolicies")).isEqualTo(List.of(custom));

        // 重新提交键序不同的同一条件不算改动，持有者的 Token 不失效。
        before = version(MEMBER);
        dataPolicies(role, List.of(Map.of("resource", "ledger", "condition", condition, "scope", "custom")));
        assertThat(version(MEMBER)).isEqualTo(before);

        // 非法表达式在保存前被拒绝，错误指出位置与原因；已保存的条件不变。
        for (var invalid : List.of(
                Map.of("attr", "resource.amount", "op", "under", "value", 1),
                Map.of("attr", "resource.amount", "op", "gt", "value", "很多"),
                Map.of("attr", "subject.attributes.missing", "op", "eq", "value", true),
                Map.of("attr", "resource.amount", "op", "between", "value", 1),
                Map.of("and", List.of(Map.of("attr", "resource.amount"))),
                // 删光条件的「全部满足」恒为真，会放开全部数据
                Map.of("and", List.of()),
                Map.of("or", List.of(condition, Map.of("or", List.of()))))) {
            var rejected =
                    dataPolicies(role, List.of(Map.of("resource", "ledger", "scope", "custom", "condition", invalid)));
            assertThat(code(rejected)).as("%s", invalid).isEqualTo("role.invalidDataCondition");
            assertThat((String) rejected.getBody().get("userTip")).startsWith("数据资源 ledger 的自定义条件不合法：");
            assertThat((String) rejected.getBody().get("errorMessage")).startsWith("Data Resource ledger: ");
        }
        assertThat(code(dataPolicies(role, List.of(Map.of("resource", "ledger", "scope", "custom")))))
                .isEqualTo("role.invalidDataCondition");
        assertThat(code(dataPolicies(
                        role, List.of(Map.of("resource", "ledger", "scope", "self", "condition", condition)))))
                .isEqualTo("role.invalidDataPolicy");
        assertThat(roleById(ADMIN, role).get("dataPolicies")).isEqualTo(List.of(custom));

        // 改回预设：条件随之清除。
        dataPolicies(role, List.of(Map.of("resource", "ledger", "scope", "all")));
        assertThat(grants(MEMBER).dataPolicies()).isEqualTo(Map.of("ledger", List.of(DataScope.ALL.template())));
        assign(ADMIN, MEMBER, List.of());
    }

    private static Map<String, Object> attribute(String name, String label, String type, boolean multiValued) {
        var attribute = new HashMap<String, Object>();
        attribute.put("name", name);
        attribute.put("label", label);
        attribute.put("type", type);
        attribute.put("multiValued", multiValued);
        return attribute;
    }

    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private static String label(String id) {
        return id.replace("-", "");
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
                                                new DataAttribute("departmentPath", "归属部门", "ltree", "department_path"),
                                                new DataAttribute("amount", "金额", "number", "amount"))),
                                new DataResource(
                                        "memo",
                                        "备忘",
                                        "memos",
                                        List.of(new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"))))
                        .filter(resource -> withMemo || !resource.code().equals("memo"))
                        .toList());
    }

    private ResponseEntity<Map> dataPolicies(String roleId, List<? extends Map<String, ?>> policies) throws Exception {
        return call(ADMIN, HttpMethod.PUT, "/roles/" + roleId + "/data-policies", Map.of("policies", policies));
    }

    private Map<?, ?> roleById(UUID admin, String id) throws Exception {
        for (Object item :
                (List<?>) call(admin, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            if (id.equals(((Map<?, ?>) item).get("id"))) {
                return (Map<?, ?>) item;
            }
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
            if (roleCode.equals(role.get("code"))) {
                return List.copyOf((List<?>) role.get("permissions"));
            }
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
            if (children != null) {
                collect((List<?>) children, codes);
            }
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
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
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
