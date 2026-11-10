package io.saasforge.iam.access.service;

import io.saasforge.iam.navigation.model.NavigationLayout;
import io.saasforge.iam.navigation.service.NavigationLayoutService;
import io.saasforge.iam.operator.model.OperatorGrants;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.plan.service.PlanService;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.subapp.model.LoadableSubApp;
import io.saasforge.iam.subapp.service.SubAppService;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 当前 Membership 的权限码、按权限与套餐裁剪后的菜单树、Shell App 可加载的 Sub-App 与租户的 Navigation Layout，供 Shell App
 * 生成路由、导航与 {@code v-perm}；以及当前 Operator 的平台角色、权限码与平台的 Navigation Layout，供 platform-console 使用。
 */
@Service
public class AccessService {
    private final RoleService roles;
    private final PermissionService permissions;
    private final PlanService plans;
    private final SubAppService subApps;
    private final PlatformRoleService platformRoles;
    private final NavigationLayoutService layouts;

    AccessService(
            RoleService roles,
            PermissionService permissions,
            PlanService plans,
            SubAppService subApps,
            PlatformRoleService platformRoles,
            NavigationLayoutService layouts) {
        this.roles = roles;
        this.permissions = permissions;
        this.plans = plans;
        this.subApps = subApps;
        this.platformRoles = platformRoles;
        this.layouts = layouts;
    }

    /**
     * 按数据库中的当前角色计算，而不是 Token 签发时发布的明细，菜单不会落后于刚生效的变更。指向套餐外 Sub-App 的菜单节点隐藏；
     * 可加载的 Sub-App 是可见菜单引用、且在 App Registry 中登记了当前环境入口地址的那些。
     */
    public Access of(UUID tenantId, UUID membershipId) {
        Set<String> granted = roles.grantsOf(membershipId).permissions();
        // Shell App 只需要导航信息，页面操作的组织关系留在授权树中。
        List<MenuNode> menus =
                PermissionService.withoutActions(permissions.visibleMenus(granted, plans.subAppsOf(tenantId)));
        Set<String> referenced = new TreeSet<>();
        collectApps(menus, referenced);
        return new Access(
                new ArrayList<>(new TreeSet<>(granted)),
                menus,
                subApps.loadable(referenced),
                layouts.tenantLayout(tenantId));
    }

    /** 当前 Operator 的平台权限与平台的布局；已停用或删除的 Operator 没有任何权限，布局仍照常下发。 */
    public PlatformAccess platformOf(UUID operatorId) {
        OperatorGrants current = platformRoles.accessOf(operatorId);
        return new PlatformAccess(
                current.roles().stream().sorted().toList(),
                current.permissions().stream().sorted().toList(),
                layouts.platformLayout());
    }

    private static void collectApps(List<MenuNode> nodes, Set<String> apps) {
        for (MenuNode node : nodes) {
            if (node.app() != null) apps.add(node.app());
            collectApps(node.children(), apps);
        }
    }

    /**
     * @param permissions 权限码，按编码排序
     * @param menus 可见菜单树
     * @param subApps 可加载的 Sub-App，按编码排序
     * @param layout 租户的 Navigation Layout，没有时为 null
     */
    public record Access(
            List<String> permissions, List<MenuNode> menus, List<LoadableSubApp> subApps, NavigationLayout layout) {}

    /**
     * @param roles 平台角色编码，按编码排序
     * @param permissions 有效平台权限码，按编码排序
     * @param layout 平台的 Navigation Layout，没有时为 null
     */
    public record PlatformAccess(List<String> roles, List<String> permissions, NavigationLayout layout) {}
}
