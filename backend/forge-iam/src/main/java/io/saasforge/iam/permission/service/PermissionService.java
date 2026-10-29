package io.saasforge.iam.permission.service;

import io.saasforge.iam.permission.mapper.PermissionMapper;
import io.saasforge.iam.permission.mapper.ResourceManifestMapper;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.model.ServiceDataResource;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 接收业务服务上报的 Resource Manifest，维护 Permission Code、菜单、角色模板与 Data Resource 的当前版本。
 *
 * <p>Tenant Realm 与 Platform Realm 服务的清单存储方式相同，读取时按 Realm 分开：不带 {@code platform} 前缀的方法只返回
 * Tenant Realm 服务申报的内容，供租户角色、套餐与菜单使用；{@code platform*} 方法只返回 Platform Realm 服务申报的内容，供平台角色使用。
 * 两边互不可见，平台权限码不能授予租户角色，反之亦然。
 */
@Service
public class PermissionService {
    private final ResourceManifestMapper manifests;
    private final PermissionMapper permissions;
    private final JsonMapper json;

    PermissionService(ResourceManifestMapper manifests, PermissionMapper permissions, JsonMapper json) {
        this.manifests = manifests;
        this.permissions = permissions;
        this.json = json;
    }

    /**
     * 存储上报的清单。新版本成为该服务的当前版本：其中的权限码新增或恢复为 active，不再出现的标记 inactive。
     * 同一版本重复上报相同内容不做任何修改，因此旧版本实例重启不会把当前版本切回旧版。
     *
     * @param reporter 上报令牌所属的服务
     * @param manifest 上报的清单
     * @return 是否存储了新版本
     */
    @Transactional
    public boolean report(String reporter, ResourceManifest manifest) {
        if (!reporter.equals(manifest.service())) throw new ForgeException(ManifestErrorCode.SERVICE_MISMATCH);
        validate(manifest);
        String content = json.writeValueAsString(manifest);
        manifests.lockService(manifest.service());
        Boolean same = manifests.sameContent(manifest.service(), manifest.version(), content);
        if (same != null) {
            if (!same) throw new ForgeException(ManifestErrorCode.VERSION_CONFLICT, manifest.version());
            return false;
        }
        requireOwnDataResources(manifest);
        manifests.clearCurrent(manifest.service());
        manifests.insertCurrent(UuidV7.generate(), manifest.service(), manifest.version(), content);
        Set<String> codes = new HashSet<>();
        for (Permission permission : manifest.permissions()) {
            codes.add(permission.code());
            int changed = permissions.upsert(
                    UuidV7.generate(),
                    permission.code(),
                    manifest.service(),
                    permission.name(),
                    permission.group(),
                    realmOf(manifest));
            if (changed == 0) throw new ForgeException(ManifestErrorCode.PERMISSION_CONFLICT, permission.code());
        }
        permissions.deactivateMissing(manifest.service(), codes);
        return true;
    }

    /** Tenant Realm 全部 active 的 Permission Code。 */
    public List<String> activeCodes() {
        return permissions.activeCodes(ResourceManifest.TENANT_REALM);
    }

    /** Tenant Realm 全部 active 的 Permission Code 及其展示信息，按服务、分组、编码排序，供角色授权界面选择。 */
    public List<PermissionRow> activePermissions() {
        return permissions.activePermissions(ResourceManifest.TENANT_REALM);
    }

    /** Platform Realm 全部 active 的 Permission Code 及其展示信息，按服务、分组、编码排序，供平台角色授权界面选择。 */
    public List<PermissionRow> activePlatformPermissions() {
        return permissions.activePermissions(ResourceManifest.PLATFORM_REALM);
    }

    /**
     * 可纳入套餐 Feature Pack 的权限码：业务服务申报的有效权限码，不含 iam 自身的管理权限码（它们不受套餐限制）。
     * 排序同 {@link #activePermissions()}。
     */
    public List<PermissionRow> planScopedPermissions() {
        return activePermissions().stream()
                .filter(permission -> !IamPermissions.SERVICE.equals(permission.service()))
                .toList();
    }

    /** 各服务当前版本的菜单树，按服务名依次拼接，未经权限裁剪。 */
    public List<MenuNode> currentMenus() {
        return currentManifests(false).stream()
                .flatMap(manifest -> manifest.menus().stream())
                .toList();
    }

    /** 各服务当前版本的角色模板，按服务名依次拼接。 */
    public List<RoleTemplate> currentRoleTemplates() {
        return currentManifests(false).stream()
                .flatMap(manifest -> manifest.roles().stream())
                .toList();
    }

    /** Tenant Realm 各服务当前版本声明的 Data Resource，按服务名、声明顺序排列；下线的资源不再出现，其上的策略不发布。 */
    public List<ServiceDataResource> currentDataResources() {
        return dataResourcesOf(currentManifests(false));
    }

    /** Platform Realm 各服务当前版本声明的 Data Resource，排序与下线规则同 {@link #currentDataResources()}。 */
    public List<ServiceDataResource> currentPlatformDataResources() {
        return dataResourcesOf(currentManifests(true));
    }

    private static List<ServiceDataResource> dataResourcesOf(List<ResourceManifest> manifests) {
        return manifests.stream()
                .flatMap(manifest -> manifest.dataResources().stream()
                        .map(resource -> new ServiceDataResource(manifest.service(), resource)))
                .toList();
    }

    // 与权限码一样以先声明者为准，两个 Realm 之间也不能重复；在同一把服务锁之外判断，两个服务同时首次声明同一编码的竞争可以接受
    // （上报只来自部署）。
    private void requireOwnDataResources(ResourceManifest manifest) {
        for (ServiceDataResource current : dataResourcesOf(currentManifests())) {
            if (current.service().equals(manifest.service())) continue;
            for (DataResource resource : manifest.dataResources()) {
                if (resource.code().equals(current.resource().code())) {
                    throw new ForgeException(ManifestErrorCode.DATA_RESOURCE_CONFLICT, resource.code());
                }
            }
        }
    }

    private List<ResourceManifest> currentManifests() {
        return manifests.currentContents().stream()
                .map(content -> json.readValue(content, ResourceManifest.class))
                .toList();
    }

    private List<ResourceManifest> currentManifests(boolean platform) {
        return currentManifests().stream()
                .filter(manifest -> manifest.platform() == platform)
                .toList();
    }

    private static String realmOf(ResourceManifest manifest) {
        return manifest.platform() ? ResourceManifest.PLATFORM_REALM : ResourceManifest.TENANT_REALM;
    }

    private static void validate(ResourceManifest manifest) {
        // 规则与 SDK 生成清单时相同；接口也可能被 CI 或其他客户端直接调用，iam 仍须自行校验。
        try {
            manifest.validate();
        } catch (IllegalArgumentException error) {
            throw new ForgeException(ManifestErrorCode.INVALID, error.getMessage());
        }
    }
}
