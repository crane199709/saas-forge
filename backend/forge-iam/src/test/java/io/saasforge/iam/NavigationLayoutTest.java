package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.CurrentTenant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 导航布局（navigation-layout 规格第 6、9 节）：两端的读取、保存与恢复默认，默认布局，结构校验、权限、审计与 access 响应。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/navigation_layout.sql")
@SuppressWarnings({"unchecked", "rawtypes"})
class NavigationLayoutTest {
    static final String KEY_FILE = "target/test-navigation-signing.jwk";
    static final String PLATFORM_KEY_FILE = "target/test-navigation-platform-signing.jwk";
    static final UUID TENANT_A = UUID.fromString("019a0200-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0200-0000-7000-8000-000000000002");
    static final UUID ADMIN_A = UUID.fromString("019a0200-0000-7000-8000-0000000000a2");
    static final UUID MEMBER_A = UUID.fromString("019a0200-0000-7000-8000-0000000000a4");
    static final UUID EDITOR_A = UUID.fromString("019a0200-0000-7000-8000-0000000000a7");
    static final UUID ADMIN_B = UUID.fromString("019a0200-0000-7000-8000-0000000000b2");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN_A, UUID.fromString("019a0200-0000-7000-8000-0000000000a1"),
            MEMBER_A, UUID.fromString("019a0200-0000-7000-8000-0000000000a3"),
            EDITOR_A, UUID.fromString("019a0200-0000-7000-8000-0000000000a6"),
            ADMIN_B, UUID.fromString("019a0200-0000-7000-8000-0000000000b1"));
    static final Map<UUID, UUID> TENANTS =
            Map.of(ADMIN_A, TENANT_A, MEMBER_A, TENANT_A, EDITOR_A, TENANT_A, ADMIN_B, TENANT_B);
    static final UUID SUPER_ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");
    static final UUID LIMITED_OPERATOR = UUID.fromString("019a0200-0000-7000-8000-0000000000f2");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String LAYOUT = """
            {"nodes": [
              {"page": "members"},
              {"group": "f3c1d2", "name": "财务", "children": [{"menu": "demo.attachments"}]},
              {"menu": "demo", "children": [{"menu": "demo.overview"}]}
            ]}
            """;

    // 迁移写入的默认布局（规格第 9 节），没有自己的布局时生效。
    static final String TENANT_DEFAULT = """
            {"nodes": [
              {"page": "announcements"},
              {"group": "org-access", "name": "组织与权限", "children": [
                {"page": "members"}, {"page": "org"}, {"page": "roles"}]},
              {"group": "security-audit", "name": "安全与审计", "children": [
                {"page": "security-settings"}, {"page": "audit"}]},
              {"group": "system-settings", "name": "系统设置", "children": [
                {"page": "dictionaries"}, {"page": "parameters"}, {"page": "menu-layout"}]}
            ]}
            """;
    static final String PLATFORM_DEFAULT = """
            {"nodes": [
              {"group": "tenant-operations", "name": "租户运营", "children": [
                {"page": "tenants"}, {"page": "plans"}, {"page": "sub-apps"}, {"page": "announcements"}]},
              {"group": "access-control", "name": "访问控制", "children": [
                {"page": "operators"}, {"page": "identities"}, {"page": "roles"}]},
              {"group": "operations", "name": "运维", "children": [
                {"page": "audit"}, {"page": "task-runs"}, {"page": "dead-events"}]},
              {"page": "menu-layout"}
            ]}
            """;

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
        registry.add("forge.iam.platform.signing-key-file", () -> PLATFORM_KEY_FILE);
    }

    @LocalServerPort
    int port;

    @Autowired
    PermissionService permissions;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transactions;

    // 业务服务上报的菜单：分组下的页面带页面操作；重复上报相同内容为无操作。
    @BeforeEach
    void reportManifest() {
        permissions.report(
                "nav-demo",
                new ResourceManifest(
                        "nav-demo",
                        "1",
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
                                List.of(new MenuNode(
                                        "orders.list",
                                        "订单列表",
                                        null,
                                        "demo",
                                        "orders",
                                        "order:read",
                                        null,
                                        List.of("order:approve")))))));
    }

    @Test
    void tenantAdminSavesReadsAndResetsTheLayout() throws Exception {
        String admin = token(ADMIN_A);
        Map<String, Object> defaults = JSON.readValue(TENANT_DEFAULT, Map.class);
        assertThat(data(call(admin, HttpMethod.GET, "/navigation-layout", null)))
                .containsEntry("layout", defaults);
        assertThat(data(call(admin, HttpMethod.GET, "/auth/access", null))).containsEntry("layout", defaults);

        var saved = call(admin, HttpMethod.PUT, "/navigation-layout", JSON.readValue(LAYOUT, Map.class));
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(data(saved)).isEqualTo(JSON.readValue(LAYOUT, Map.class));
        assertThat(data(call(admin, HttpMethod.GET, "/navigation-layout", null)))
                .containsEntry("layout", JSON.readValue(LAYOUT, Map.class));
        assertThat(data(call(admin, HttpMethod.GET, "/auth/access", null)))
                .containsEntry("layout", JSON.readValue(LAYOUT, Map.class));

        var reset = call(admin, HttpMethod.DELETE, "/navigation-layout", null);
        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(reset.getBody().get("data")).isNull();
        assertThat(data(call(admin, HttpMethod.GET, "/navigation-layout", null)))
                .containsEntry("layout", defaults);
        assertThat(data(call(admin, HttpMethod.GET, "/auth/access", null))).containsEntry("layout", defaults);
    }

    @Test
    void catalogIsTrimmedByThePlanButNotByTheOperatorsOwnPermissionsAndHasNoActions() throws Exception {
        // 菜单编辑者没有 order:read，仍能看到需要它的页面
        Map<String, Object> catalog = data(call(token(EDITOR_A), HttpMethod.GET, "/navigation-layout", null));
        List<Map<String, Object>> menus = (List<Map<String, Object>>) catalog.get("menus");
        Map<String, Object> orders = menus.stream()
                .filter(menu -> "orders".equals(menu.get("code")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> children = (List<Map<String, Object>>) orders.get("children");
        assertThat(children)
                .singleElement()
                .satisfies(page -> assertThat(page)
                        .containsEntry("code", "orders.list")
                        .containsEntry("permission", "order:read")
                        .doesNotContainKey("actions"));
        assertThat(data(call(token(EDITOR_A), HttpMethod.GET, "/auth/access", null))
                        .get("permissions"))
                .isEqualTo(List.of("iam:menu:manage"));

        // 套餐外的 Sub-App 不在目录中
        deliver(new TenantPlanChanged(
                TENANT_B,
                UUID.fromString("019a0200-0000-7000-8000-0000000000c1"),
                "basic",
                List.of("order:read"),
                List.of("other"),
                null,
                null,
                1));
        List<Map<String, Object>> trimmed =
                (List<Map<String, Object>>) data(call(token(ADMIN_B), HttpMethod.GET, "/navigation-layout", null))
                        .get("menus");
        assertThat(trimmed).noneSatisfy(menu -> assertThat(menu).containsEntry("code", "orders"));
    }

    @Test
    void operatorSavesReadsAndResetsThePlatformLayoutKeepingOneRecord() throws Exception {
        String admin = platformToken(SUPER_ADMIN);
        Map<String, Object> defaults = JSON.readValue(PLATFORM_DEFAULT, Map.class);
        assertThat(data(call(admin, HttpMethod.GET, "/platform/navigation-layout", null)))
                .containsEntry("layout", defaults);
        Map<String, Object> access = data(call(admin, HttpMethod.GET, "/platform/access", null));
        assertThat(access).containsEntry("layout", defaults).containsEntry("roles", List.of("super-admin"));
        assertThat((List<Object>) access.get("permissions")).contains("platform:menu:manage");

        Map<String, Object> first = Map.of("nodes", List.of(Map.of("page", "tenants"), Map.of("page", "plans")));
        assertThat(data(call(admin, HttpMethod.PUT, "/platform/navigation-layout", first)))
                .isEqualTo(first);
        Map<String, Object> second = JSON.readValue(LAYOUT, Map.class);
        assertThat(data(call(admin, HttpMethod.PUT, "/platform/navigation-layout", second)))
                .isEqualTo(second);
        assertThat(data(call(admin, HttpMethod.GET, "/platform/navigation-layout", null)))
                .containsEntry("layout", second);
        assertThat(data(call(admin, HttpMethod.GET, "/platform/access", null))).containsEntry("layout", second);

        var reset = call(admin, HttpMethod.DELETE, "/platform/navigation-layout", null);
        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(reset.getBody().get("data")).isNull();
        assertThat(data(call(admin, HttpMethod.GET, "/platform/navigation-layout", null)))
                .containsEntry("layout", defaults);
        assertThat(data(call(admin, HttpMethod.GET, "/platform/access", null))).containsEntry("layout", defaults);

        // 恢复默认后可以再次保存
        assertThat(data(call(admin, HttpMethod.PUT, "/platform/navigation-layout", first)))
                .isEqualTo(first);
        assertThat(data(call(admin, HttpMethod.GET, "/platform/navigation-layout", null)))
                .containsEntry("layout", first);
        call(admin, HttpMethod.DELETE, "/platform/navigation-layout", null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{'nodes': [{}]}",
                "{'nodes': [{'page': 'members', 'menu': 'demo'}]}",
                "{'nodes': [{'menu': 'demo', 'group': 'g', 'name': '组'}]}",
                "{'nodes': [{'page': ' '}]}",
                "{'nodes': [{'page': 'members', 'children': [{'page': 'roles'}]}]}",
                "{'nodes': [{'group': 'g1', 'name': '一', 'children': [{'group': 'g2', 'name': '二',"
                        + " 'children': [{'menu': 'demo', 'children': [{'menu': 'demo.overview'}]}]}]}]}",
                "{'nodes': [{'page': 'members'}, {'group': 'g', 'name': '组', 'children': [{'page': 'members'}]}]}",
                "{'nodes': [{'menu': 'demo'}, {'menu': 'demo'}]}",
                "{'nodes': [{'group': 'g', 'name': '甲'}, {'group': 'g', 'name': '乙'}]}",
                "{'nodes': [{'group': '', 'name': '组'}]}",
                "{'nodes': [{'group': 'g'}]}",
                "{'nodes': [{'group': 'g', 'name': ' '}]}",
                "{'nodes': [{'group': 'g', 'name': '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三'}]}"
            })
    void structurallyInvalidLayoutsAreRejectedInBothRealms(String layout) throws Exception {
        Map<String, Object> body = JSON.readValue(layout.replace('\'', '"'), Map.class);
        for (var response : List.of(
                call(token(ADMIN_A), HttpMethod.PUT, "/navigation-layout", body),
                call(platformToken(SUPER_ADMIN), HttpMethod.PUT, "/platform/navigation-layout", body))) {
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("code", "navigation.invalidLayout");
        }
    }

    @Test
    void depthNameIdAndNodeCountLimitsAreInclusive() throws Exception {
        String admin = token(ADMIN_A);
        String longId = "g".repeat(64);
        Map<String, Object> deepest = Map.of(
                "nodes",
                List.of(Map.of(
                        "group",
                        longId,
                        "name",
                        "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二",
                        "children",
                        List.of(Map.of("menu", "demo", "children", List.of(Map.of("menu", "demo.overview")))))));
        assertThat(call(admin, HttpMethod.PUT, "/navigation-layout", deepest)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        Map<String, Object> longerId = Map.of("nodes", List.of(Map.of("group", longId + "g", "name", "组")));
        assertThat(call(admin, HttpMethod.PUT, "/navigation-layout", longerId).getBody())
                .containsEntry("code", "navigation.invalidLayout");

        assertThat(call(admin, HttpMethod.PUT, "/navigation-layout", pages(500))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(admin, HttpMethod.PUT, "/navigation-layout", pages(501)).getBody())
                .containsEntry("code", "navigation.invalidLayout");
        call(admin, HttpMethod.DELETE, "/navigation-layout", null);
    }

    @Test
    void managingTheLayoutRequiresTheMenuPermissionButReadingAccessDoesNot() throws Exception {
        String member = token(MEMBER_A);
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            var denied = call(
                    member, method, "/navigation-layout", method == HttpMethod.PUT ? Map.of("nodes", List.of()) : null);
            assertThat(denied.getStatusCode().value()).isEqualTo(403);
            assertThat(denied.getBody()).containsEntry("code", "auth.forbidden");
        }
        assertThat(data(call(member, HttpMethod.GET, "/auth/access", null))).containsKey("layout");

        String operator = platformToken(LIMITED_OPERATOR);
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            var denied = call(
                    operator,
                    method,
                    "/platform/navigation-layout",
                    method == HttpMethod.PUT ? Map.of("nodes", List.of()) : null);
            assertThat(denied.getStatusCode().value()).isEqualTo(403);
            assertThat(denied.getBody()).containsEntry("code", "auth.forbidden");
        }
        assertThat(data(call(operator, HttpMethod.GET, "/platform/access", null)))
                .containsKey("layout");

        // 两个 Realm 的 Token 不能互用
        assertThat(call(token(ADMIN_A), HttpMethod.GET, "/platform/navigation-layout", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(call(platformToken(SUPER_ADMIN), HttpMethod.GET, "/navigation-layout", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    @Test
    void savingAndResettingAreAuditedInTheirRealm() throws Exception {
        String admin = token(ADMIN_A);
        int before = audits(AuditRealm.TENANT).size();
        call(admin, HttpMethod.PUT, "/navigation-layout", JSON.readValue(LAYOUT, Map.class));
        call(admin, HttpMethod.DELETE, "/navigation-layout", null);
        List<AuditEvent> tenant = audits(AuditRealm.TENANT);
        assertThat(tenant.subList(before, tenant.size()))
                .extracting(AuditEvent::action, AuditEvent::tenantId, AuditEvent::actorId)
                .containsExactly(
                        tuple("navigationLayout.replace", TENANT_A, IDENTITIES.get(ADMIN_A)),
                        tuple("navigationLayout.reset", TENANT_A, IDENTITIES.get(ADMIN_A)));

        String operator = platformToken(SUPER_ADMIN);
        int platformBefore = audits(AuditRealm.PLATFORM).size();
        call(operator, HttpMethod.PUT, "/platform/navigation-layout", JSON.readValue(LAYOUT, Map.class));
        call(operator, HttpMethod.DELETE, "/platform/navigation-layout", null);
        List<AuditEvent> platform = audits(AuditRealm.PLATFORM);
        assertThat(platform.subList(platformBefore, platform.size()))
                .extracting(AuditEvent::action, AuditEvent::actorId)
                .containsExactly(
                        tuple("navigationLayout.replace", SUPER_ADMIN), tuple("navigationLayout.reset", SUPER_ADMIN));
    }

    @Test
    void tenantsDoNotSeeEachOthersLayout() throws Exception {
        String adminA = token(ADMIN_A);
        assertThat(call(adminA, HttpMethod.PUT, "/navigation-layout", JSON.readValue(LAYOUT, Map.class))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        String adminB = token(ADMIN_B);
        Map<String, Object> defaults = JSON.readValue(TENANT_DEFAULT, Map.class);
        assertThat(data(call(adminB, HttpMethod.GET, "/navigation-layout", null)))
                .containsEntry("layout", defaults);
        assertThat(data(call(adminB, HttpMethod.GET, "/auth/access", null))).containsEntry("layout", defaults);
        // 同租户的其他成员在下次读取权限时拿到新布局
        assertThat(data(call(token(MEMBER_A), HttpMethod.GET, "/auth/access", null)))
                .containsEntry("layout", JSON.readValue(LAYOUT, Map.class));
        call(adminA, HttpMethod.DELETE, "/navigation-layout", null);
    }

    @Test
    void theMigratedDefaultLayoutsPassTheStructuralValidation() throws Exception {
        String tenantAdmin = token(ADMIN_A);
        Object tenantDefault = data(call(tenantAdmin, HttpMethod.GET, "/navigation-layout", null))
                .get("layout");
        assertThat(call(tenantAdmin, HttpMethod.PUT, "/navigation-layout", tenantDefault)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        call(tenantAdmin, HttpMethod.DELETE, "/navigation-layout", null);

        String operator = platformToken(SUPER_ADMIN);
        Object platformDefault = data(call(operator, HttpMethod.GET, "/platform/navigation-layout", null))
                .get("layout");
        assertThat(call(operator, HttpMethod.PUT, "/platform/navigation-layout", platformDefault)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        call(operator, HttpMethod.DELETE, "/platform/navigation-layout", null);
    }

    @Test
    @Sql(scripts = {"/fixtures/navigation_layout.sql", "/fixtures/navigation_default_layout_removed.sql"})
    @Sql(
            scripts = "/fixtures/navigation_default_layout_restored.sql",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void withoutAnyDefaultLayoutTheLayoutIsNull() throws Exception {
        String admin = token(ADMIN_A);
        assertThat(data(call(admin, HttpMethod.GET, "/navigation-layout", null)))
                .containsEntry("layout", null);
        assertThat(data(call(admin, HttpMethod.GET, "/auth/access", null))).containsEntry("layout", null);

        String operator = platformToken(SUPER_ADMIN);
        assertThat(data(call(operator, HttpMethod.GET, "/platform/navigation-layout", null)))
                .containsEntry("layout", null);
        assertThat(data(call(operator, HttpMethod.GET, "/platform/access", null)))
                .containsEntry("layout", null);
    }

    private static Map<String, Object> pages(int count) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(Map.of("page", "page-" + i));
        }
        return Map.of("nodes", nodes);
    }

    private List<AuditEvent> audits(AuditRealm realm) {
        // 测试不连接 MQ；读取待投递消息体，验证 HTTP 操作产出的审计事件契约。
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> event.realm() == realm && event.action().startsWith("navigationLayout."))
                .toList();
    }

    /** 与 SDK 消费端一致：先恢复发布方的租户，再在事务中调用处理器。 */
    private void deliver(TenantPlanChanged event) {
        var handler = (EventHandler<TenantPlanChanged>) handlers.stream()
                .filter(candidate -> candidate.eventType() == TenantPlanChanged.class)
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(event.tenantId(), () -> transactions.executeWithoutResult(status -> handler.handle(event)));
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    private ResponseEntity<Map> call(String token, HttpMethod method, String uri, Object body) {
        var request =
                RestClient.create("http://127.0.0.1:" + port).method(method).uri(uri);
        if (token != null) {
            request.headers(headers -> headers.setBearerAuth(token));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private static String token(UUID membership) throws Exception {
        return signed(
                KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam")
                        .subject(IDENTITIES.get(membership).toString())
                        .audience("tenant-app")
                        .claim("tenant_id", TENANTS.get(membership).toString())
                        .claim("membership_id", membership.toString())
                        .claim("ver", 1));
    }

    private static String platformToken(UUID operator) throws Exception {
        return signed(
                PLATFORM_KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam/platform")
                        .subject(operator.toString())
                        .audience("platform-console")
                        .claim("ver", 1));
    }

    private static String signed(String file, JWTClaimsSet.Builder claims) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(file)));
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
