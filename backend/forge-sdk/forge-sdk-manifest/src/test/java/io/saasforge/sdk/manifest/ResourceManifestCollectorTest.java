package io.saasforge.sdk.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ResourceManifestCollectorTest {
    static final String FIXTURES = "io.saasforge.sdk.manifest.fixture";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void scannedCodesAreMergedWithNamesGroupsAndMenusFromTheManifestFile() {
        ResourceManifest manifest = collect("manifests/valid.json");

        assertThat(manifest.service()).isEqualTo("demo-service");
        assertThat(manifest.version()).isEqualTo("1.0.0");
        // 扫描到的编码与清单文件中只给前端按钮用的编码合并，按编码排序；文件未命名的编码以编码本身作名称。
        assertThat(manifest.permissions())
                .containsExactly(
                        new Permission("order:approve", "order:approve", null),
                        new Permission("order:export", "导出订单", "订单"),
                        new Permission("order:read", "查看订单", "订单"));
        assertThat(manifest.menus())
                .containsExactly(new MenuNode(
                        "orders",
                        "订单",
                        "list",
                        null,
                        null,
                        null,
                        List.of(new MenuNode(
                                "orders.overview", "订单概览", null, "demo", "overview", "order:read", null))));
        assertThat(manifest.roles()).containsExactly(new RoleTemplate("order-viewer", "订单查看员", List.of("order:read")));
        assertThat(manifest.dataResources())
                .containsExactly(new DataResource(
                        "order",
                        "订单",
                        "orders",
                        List.of(
                                new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"),
                                new DataAttribute("departmentPath", "归属部门", "ltree", "department_path"))));
        // 数据权限拦截器只需要数据资源，不必扫描权限码
        assertThat(ResourceManifestCollector.dataResources(
                        ResourceManifestCollectorTest.class.getClassLoader(), "manifests/valid.json"))
                .isEqualTo(manifest.dataResources());
    }

    @Test
    void pageActionsAreCollectedAndPreservedWhenReplacingChildren() {
        ResourceManifest manifest = collect("manifests/actions.json");
        MenuNode page = manifest.menus().get(0);
        assertThat(page.actions()).containsExactly("order:approve");
        assertThat(page.withChildren(List.of()).actions()).containsExactly("order:approve");
        assertThat(JSON.writeValueAsString(page)).contains("\"actions\":[\"order:approve\"]");
        MenuNode legacy =
                collect("manifests/valid.json").menus().get(0).children().get(0);
        assertThat(legacy.actions()).isEmpty();
        assertThat(JSON.writeValueAsString(legacy))
                .isEqualTo(
                        "{\"code\":\"orders.overview\",\"name\":\"订单概览\",\"app\":\"demo\",\"path\":\"overview\",\"permission\":\"order:read\",\"children\":[]}");
    }

    @ParameterizedTest
    @CsvSource({"action-group,leaf", "action-unknown,order:typo", "action-duplicate,Duplicate", "action-page,permission"
    })
    void invalidPageActionsAreRejected(String fixture, String reason) {
        assertThatThrownBy(() -> collect("manifests/" + fixture + ".json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void missingManifestFileYieldsScannedCodesAndNoMenus() {
        ResourceManifest manifest = collect("manifests/absent.json");

        assertThat(manifest.permissions()).extracting(Permission::code).containsExactly("order:approve", "order:read");
        assertThat(manifest.menus()).isEmpty();
    }

    @Test
    void platformRealmIsReadFromTheManifestFileAndOmittedForTenantRealmServices() {
        ResourceManifest platform = collect("manifests/platform.json");
        assertThat(platform.realm()).isEqualTo(ResourceManifest.PLATFORM_REALM);
        assertThat(platform.platform()).isTrue();
        assertThat(JSON.writeValueAsString(platform)).contains("\"realm\":\"platform\"");

        // 已存储的 Tenant Realm 清单不带 realm，新生成的清单按内容比较仍与之相同
        ResourceManifest tenant = collect("manifests/valid.json");
        assertThat(tenant.realm()).isNull();
        assertThat(JSON.writeValueAsString(tenant)).doesNotContain("realm").doesNotContain("platform");
        assertThat(new ResourceManifest("s", "1", List.of(), List.of(), List.of(), List.of(), "tenant").realm())
                .isNull();

        assertThatThrownBy(() -> collect("manifests/unknown-realm.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("realm");
        assertThatThrownBy(() -> collect("manifests/platform-role-template.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("role templates");
    }

    @Test
    void menuReferringToAnUndeclaredPermissionIsRejected() {
        assertThatThrownBy(() -> collect("manifests/unknown-menu-permission.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order:typo");
    }

    @Test
    void roleTemplatesMustGrantDeclaredCodesAndCannotTakeBuiltinRoleCodes() {
        assertThatThrownBy(() -> collect("manifests/unknown-role-permission.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order:typo");
        assertThatThrownBy(() -> collect("manifests/reserved-role-code.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-admin");
    }

    @Test
    void dataAttributesMustHaveAKnownTypeAndAPlainColumnName() {
        assertThatThrownBy(() -> collect("manifests/invalid-data-attribute-type.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("date");
        // 列名会进入 SQL，不能借清单注入
        assertThatThrownBy(() -> collect("manifests/invalid-data-column.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DROP TABLE");
        assertThatThrownBy(() -> ResourceManifestCollector.dataResources(
                        ResourceManifestCollectorTest.class.getClassLoader(), "manifests/invalid-data-column.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ResourceManifest collect(String location) {
        return ResourceManifestCollector.collect(
                "demo-service", "1.0.0", FIXTURES, ResourceManifestCollectorTest.class.getClassLoader(), location);
    }
}
