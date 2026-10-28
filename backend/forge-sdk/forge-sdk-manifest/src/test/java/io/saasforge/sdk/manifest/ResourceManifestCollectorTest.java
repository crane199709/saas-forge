package io.saasforge.sdk.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceManifestCollectorTest {
    static final String FIXTURES = "io.saasforge.sdk.manifest.fixture";

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
    }

    @Test
    void missingManifestFileYieldsScannedCodesAndNoMenus() {
        ResourceManifest manifest = collect("manifests/absent.json");

        assertThat(manifest.permissions()).extracting(Permission::code).containsExactly("order:approve", "order:read");
        assertThat(manifest.menus()).isEmpty();
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

    private static ResourceManifest collect(String location) {
        return ResourceManifestCollector.collect(
                "demo-service", "1.0.0", FIXTURES, ResourceManifestCollectorTest.class.getClassLoader(), location);
    }
}
