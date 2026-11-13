package io.saasforge.iam.permission.service;

import io.saasforge.iam.permission.mapper.PermissionMapper;
import io.saasforge.iam.permission.mapper.ResourceManifestMapper;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.model.ServiceDataResource;
import io.saasforge.iam.permission.model.ServiceDictionary;
import io.saasforge.iam.permission.model.ServiceParameter;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    private final List<ManifestPolicy> policies;

    PermissionService(
            ResourceManifestMapper manifests,
            PermissionMapper permissions,
            JsonMapper json,
            List<ManifestPolicy> policies) {
        this.manifests = manifests;
        this.permissions = permissions;
        this.json = json;
        this.policies = List.copyOf(policies);
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
        if (!reporter.equals(manifest.service())) {
            throw new ForgeException(ManifestErrorCode.SERVICE_MISMATCH);
        }
        validate(manifest);
        policies.forEach(policy -> policy.validate(manifest));
        String content = json.writeValueAsString(manifest);
        manifests.lockService(manifest.service());
        Boolean same = manifests.sameContent(manifest.service(), manifest.version(), content);
        if (same != null) {
            if (!same) {
                throw new ForgeException(ManifestErrorCode.VERSION_CONFLICT, manifest.version());
            }
            return false;
        }
        requireOwnDataResources(manifest);
        ResourceManifest previous = currentManifests().stream()
                .filter(value -> value.service().equals(manifest.service()))
                .findFirst()
                .orElse(null);
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
            if (changed == 0) {
                throw new ForgeException(ManifestErrorCode.PERMISSION_CONFLICT, permission.code());
            }
        }
        permissions.deactivateMissing(manifest.service(), codes);
        policies.forEach(policy -> policy.registered(previous, manifest));
        return true;
    }

    /** 已上报当前 Resource Manifest 的服务，跨 Realm、按服务名排序；配置了令牌但尚未上报的服务不在目录中。 */
    public List<String> reportedServices() {
        // forge-iam-platform 只为分开登记 Platform Realm 权限而存在，部署实例仍注册为 forge-iam。
        return currentManifests().stream()
                .map(ResourceManifest::service)
                .filter(service -> !PlatformPermissions.SERVICE.equals(service))
                .toList();
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
     * 可纳入套餐 Feature Pack 的权限码：业务服务与 IAM 成员消息读取的有效权限码，不含 IAM 的管理权限码（它们不受套餐限制）。
     * 排序同 {@link #activePermissions()}。
     */
    public List<PermissionRow> planScopedPermissions() {
        return activePermissions().stream()
                .filter(permission -> (!IamPermissions.SERVICE.equals(permission.service())
                        || IamPermissions.planScoped(permission.code())))
                .toList();
    }

    /** 各服务当前版本的菜单树，按服务名依次拼接，未经权限裁剪。 */
    public List<MenuNode> currentMenus() {
        return currentManifests(false).stream()
                .flatMap(manifest -> manifest.menus().stream())
                .toList();
    }

    /** 按调用方提供的权限码与 Plan 裁剪当前菜单，保留 actions 供各接口自行投影。 */
    public List<MenuNode> visibleMenus(Set<String> allowed, Optional<Set<String>> subApps) {
        return visible(currentMenus(), allowed, subApps);
    }

    /**
     * 判断一组权限码能否看到给定 Sub-App 中的菜单，裁剪规则与 {@link #visibleMenus} 相同。菜单只读取一次，批量判断多个 Membership 时不随人数增加查询。
     *
     * @param subApps Sub-App 编码
     * @return 接收权限码集合的判断函数
     */
    public Predicate<Set<String>> seesMenuIn(Collection<String> subApps) {
        List<MenuNode> menus = currentMenus();
        Optional<Set<String>> apps = Optional.of(new HashSet<>(subApps));
        return allowed -> !visible(menus, allowed, apps).isEmpty();
    }

    /** 去掉菜单树中的页面操作：导航与布局只需要节点本身，页面操作的组织关系留在授权树中。 */
    public static List<MenuNode> withoutActions(List<MenuNode> nodes) {
        return nodes.stream()
                .map(node -> new MenuNode(
                        node.code(),
                        node.name(),
                        node.icon(),
                        node.app(),
                        node.path(),
                        node.permission(),
                        withoutActions(node.children())))
                .toList();
    }

    // 缺少权限的节点连同子树隐藏；Plan 外叶子和裁剪后为空的分组隐藏。未登记 Plan 时不限 Sub-App。
    private static List<MenuNode> visible(List<MenuNode> nodes, Set<String> allowed, Optional<Set<String>> subApps) {
        List<MenuNode> result = new ArrayList<>();
        for (MenuNode node : nodes) {
            if (node.permission() != null && !allowed.contains(node.permission())) {
                continue;
            }
            if (node.children().isEmpty()) {
                if (subApps.isEmpty() || subApps.get().contains(node.app())) {
                    result.add(node);
                }
                continue;
            }
            List<MenuNode> children = visible(node.children(), allowed, subApps);
            if (!children.isEmpty()) {
                result.add(node.withChildren(children));
            }
        }
        return result;
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

    /** Tenant Realm 各服务当前版本声明的 Dictionary，按服务名、声明顺序排列。 */
    public List<ServiceDictionary> currentDictionaries() {
        return dictionariesOf(currentManifests(false));
    }

    /**
     * Tenant Realm 各服务非当前版本中声明的 Dictionary，按版本存储时间从新到旧排列，同一字典可能出现多次。
     * 供参考数据找出被新版本删除的字典项：业务数据仍保存这些编码，需要它们最后一次声明的名称。
     */
    public List<ServiceDictionary> previousDictionaries() {
        return dictionariesOf(manifests.previousContents().stream()
                .map(content -> json.readValue(content, ResourceManifest.class))
                .filter(manifest -> !manifest.platform())
                .toList());
    }

    /** Tenant Realm 各服务当前版本声明的 Parameter，按服务名、声明顺序排列；新版本删除的参数不再出现。 */
    public List<ServiceParameter> currentParameters() {
        return currentManifests(false).stream()
                .flatMap(manifest -> manifest.parameters().stream()
                        .map(parameter -> new ServiceParameter(manifest.service(), parameter)))
                .toList();
    }

    /** 当前有效清单的敏感字段；调用方只经此服务边界读取登记。 */
    public List<ServiceSensitiveField> currentSensitiveFields() {
        return currentManifests().stream()
                .flatMap(manifest -> manifest.sensitiveFields().stream()
                        .map(field -> new ServiceSensitiveField(manifest.service(), field)))
                .toList();
    }

    /** 敏感字段的服务限定，字段路径只在同一服务内唯一。 */
    public record ServiceSensitiveField(String service, ResourceManifest.SensitiveField field) {}

    private static List<ServiceDictionary> dictionariesOf(List<ResourceManifest> manifests) {
        return manifests.stream()
                .flatMap(manifest -> manifest.dictionaries().stream()
                        .map(dictionary -> new ServiceDictionary(manifest.service(), dictionary)))
                .toList();
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
            if (current.service().equals(manifest.service())) {
                continue;
            }
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
