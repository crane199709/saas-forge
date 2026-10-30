package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 平台侧权限（工单 22）：forge-platform 作为 Platform Realm 服务申报权限码与数据资源，接口按 Operator 的平台角色明细校验功能权限，
 * 租户列表、读取与修改按 Operator 的 Data Policy 过滤。明细由 PlatformTokens 模拟 iam 发布。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plan.sql")
class PlatformPermissionTest {
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};
    static final String REGION_POLICY =
            "{\"attr\":\"resource.region\",\"op\":\"eq\",\"value\":{\"ref\":\"subject.attributes.region\"}}";
    static final String READ = "platform:tenant:read";
    static final String MANAGE = "platform:tenant:manage";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        PlatformTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void manifestDeclaresPlatformRealmPermissionsAndTheTenantDataResource() {
        ResourceManifest manifest = ResourceManifestCollector.collect(
                "forge-platform", "1", "io.saasforge.platform", getClass().getClassLoader());

        assertThat(manifest.platform()).isTrue();
        assertThat(manifest.permissions())
                .extracting(Permission::code)
                .containsExactlyInAnyOrderElementsOf(PlatformTokens.ALL_PERMISSIONS);
        // 每个声明的权限码都有名称，不以编码充当名称
        assertThat(manifest.permissions())
                .allMatch(permission -> !permission.name().equals(permission.code()));
        assertThat(manifest.dataResources()).singleElement().satisfies(resource -> {
            assertThat(resource.code()).isEqualTo("tenant");
            assertThat(resource.attributes())
                    .extracting(DataAttribute::code)
                    .containsExactly("ownerId", "region", "status");
        });
    }

    @Test
    void restrictedOperatorOnlySeesTenantsInItsRegionAndCannotManageThem() throws Exception {
        String admin = PlatformTokens.operator();
        UUID east = create(admin, "华东租户", "east").id();
        UUID west = create(admin, "华西租户", "west").id();
        UUID unassigned = create(admin, "未分区租户", null).id();

        String viewer = PlatformTokens.operator(UUID.randomUUID(), List.of(READ), "east", List.of(REGION_POLICY));
        Response page = call(viewer, "GET", "/tenants?size=100", null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.list())
                .extracting(item -> item.get("id"))
                .contains(east.toString())
                .doesNotContain(west.toString(), unassigned.toString());
        assertThat(page.list()).allMatch(item -> "east".equals(item.get("region")));
        // 分页总数同样只计范围内的租户
        assertThat(((Map<?, ?>) ((Map<?, ?>) page.body().get("meta")).get("page")).get("totalElements"))
                .isEqualTo(page.list().size());
        assertThat(call(viewer, "GET", "/tenants/" + east, null).status()).isEqualTo(200);
        // 范围外的租户与不存在的一样
        assertThat(call(viewer, "GET", "/tenants/" + west, null).code()).isEqualTo("tenant.notFound");

        // 没有管理权限码：创建、迁移状态、更换套餐都被拒
        assertThat(call(viewer, "POST", "/tenants", tenant("越权", "east")).status())
                .isEqualTo(403);
        assertThat(call(viewer, "POST", "/tenants/" + east + "/transitions", Map.of("status", "suspended"))
                        .code())
                .isEqualTo("auth.forbidden");
        // 请求体校验先于权限校验，这里用不带请求体的接口
        assertThat(call(viewer, "DELETE", "/plans/" + UUID.randomUUID(), null).status())
                .isEqualTo(403);
        assertThat(call(viewer, "DELETE", "/sub-apps/" + UUID.randomUUID(), null)
                        .status())
                .isEqualTo(403);
        // 套餐与注册表的列表供其他面板选择，只要求已认证
        assertThat(call(viewer, "GET", "/plans", null).status()).isEqualTo(200);
        assertThat(call(viewer, "GET", "/plans/permissions", null).status()).isEqualTo(403);
    }

    @Test
    void managerCanOnlyCreateAndChangeTenantsWithinItsScope() throws Exception {
        String admin = PlatformTokens.operator();
        UUID west = create(admin, "华西客户", "west").id();
        String manager =
                PlatformTokens.operator(UUID.randomUUID(), List.of(READ, MANAGE), "east", List.of(REGION_POLICY));

        // 新租户须在自己的范围内，否则创建出自己看不到的租户
        assertThat(call(manager, "POST", "/tenants", tenant("跨区", "west")).code())
                .isEqualTo("auth.forbidden");
        Response created = call(manager, "POST", "/tenants", tenant("本区", "east"));
        assertThat(created.status()).isEqualTo(201);
        UUID east = UUID.fromString((String) created.tenant().get("id"));
        assertThat(call(manager, "POST", "/tenants/" + east + "/transitions", Map.of("status", "suspended"))
                        .status())
                .isEqualTo(200);

        assertThat(call(manager, "POST", "/tenants/" + west + "/transitions", Map.of("status", "suspended"))
                        .code())
                .isEqualTo("tenant.notFound");
        assertThat(call(manager, "PUT", "/tenants/" + west + "/plan", Map.of("planId", PlatformTokens.DEFAULT_PLAN_ID))
                        .code())
                .isEqualTo("tenant.notFound");
        assertThat(call(admin, "GET", "/tenants/" + west, null).tenant()).containsEntry("status", "active");
    }

    @Test
    void transitionCannotMoveATenantOutOfTheOperatorsScope() throws Exception {
        String admin = PlatformTokens.operator();
        UUID first = create(admin, "状态范围一", null).id();
        UUID second = create(admin, "状态范围二", null).id();
        // 只管理正常或已停用的租户
        String policy = "{\"or\":[{\"attr\":\"resource.status\",\"op\":\"eq\",\"value\":\"active\"},"
                + "{\"attr\":\"resource.status\",\"op\":\"eq\",\"value\":\"suspended\"}]}";
        String manager = PlatformTokens.operator(UUID.randomUUID(), List.of(READ, MANAGE), null, List.of(policy));

        // 迁移后仍在范围内：允许
        assertThat(call(manager, "POST", "/tenants/" + first + "/transitions", Map.of("status", "suspended"))
                        .status())
                .isEqualTo(200);
        // 迁移后超出范围：拒绝，状态不变
        assertThat(call(manager, "POST", "/tenants/" + second + "/transitions", Map.of("status", "expired"))
                        .code())
                .isEqualTo("auth.forbidden");
        assertThat(call(admin, "GET", "/tenants/" + second, null).tenant()).containsEntry("status", "active");
        // 范围为「全部」的 Operator 不受影响
        assertThat(call(admin, "POST", "/tenants/" + second + "/transitions", Map.of("status", "expired"))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void selfScopeShowsOnlyTenantsTheOperatorCreatedAndNoPolicyShowsNothing() throws Exception {
        String admin = PlatformTokens.operator();
        UUID others = create(admin, "他人创建", null).id();
        UUID creator = UUID.randomUUID();
        String own = PlatformTokens.operator(creator, List.of(READ, MANAGE), null, List.of(DataScope.SELF.template()));
        Response created = call(own, "POST", "/tenants", tenant("自己创建", null));
        assertThat(created.status()).isEqualTo(201);

        assertThat(call(own, "GET", "/tenants?size=100", null).list())
                .extracting(item -> item.get("id"))
                .containsExactly(created.tenant().get("id"));
        assertThat(call(own, "GET", "/tenants/" + others, null).status()).isEqualTo(404);

        // 没有任何策略：默认拒绝
        String none = PlatformTokens.operator(UUID.randomUUID(), List.of(READ), "east", List.of());
        assertThat(call(none, "GET", "/tenants?size=100", null).list()).isEmpty();
    }

    @Test
    void planUsageIgnoresTheOperatorsTenantScope() throws Exception {
        String admin = PlatformTokens.operator();
        String planId = (String) call(
                        admin,
                        "POST",
                        "/plans",
                        Map.of("code", "scoped-plan", "name", "范围套餐", "permissions", List.of(), "subApps", List.of()))
                .data()
                .get("id");
        Map<String, Object> body = tenant("用套餐的租户", "west");
        body.put("planId", planId);
        assertThat(call(admin, "POST", "/tenants", body).status()).isEqualTo(201);

        // 看不到任何租户的 Operator 也不能删除仍被使用的套餐
        String planner = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:plan:manage"), "east", List.of());
        assertThat(call(planner, "DELETE", "/plans/" + planId, null).code()).isEqualTo("plan.inUse");
    }

    private Created create(String token, String name, String region) {
        Response response = call(token, "POST", "/tenants", tenant(name, region));
        assertThat(response.status()).isEqualTo(201);
        return new Created(UUID.fromString((String) response.tenant().get("id")));
    }

    private static Map<String, Object> tenant(String name, String region) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("adminContact", "owner@example.com");
        body.put("planId", PlatformTokens.DEFAULT_PLAN_ID);
        if (region != null) body.put("region", region);
        return body;
    }

    private Response call(String token, String method, String path, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(HttpMethod.valueOf(method))
                .uri(path)
                .header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange(
                (req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(ENVELOPE)));
    }

    record Created(UUID id) {}

    record Response(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> tenant() {
            return (Map<String, Object>) data().get("tenant");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) body.get("data");
        }

        String code() {
            return (String) body.get("code");
        }
    }
}
