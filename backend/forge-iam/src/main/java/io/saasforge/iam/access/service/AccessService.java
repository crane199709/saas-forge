package io.saasforge.iam.access.service;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.plan.service.PlanService;
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
 * 当前 Membership 的权限码、按权限与套餐裁剪后的菜单树，以及 Shell App 可加载的 Sub-App，供 Shell App 生成路由、导航与
 * {@code v-perm}。
 */
@Service
public class AccessService {
    private final RoleService roles;
    private final PermissionService permissions;
    private final PlanService plans;
    private final SubAppService subApps;

    AccessService(RoleService roles, PermissionService permissions, PlanService plans, SubAppService subApps) {
        this.roles = roles;
        this.permissions = permissions;
        this.plans = plans;
        this.subApps = subApps;
    }

    /**
     * 按数据库中的当前角色计算，而不是 Token 签发时发布的明细，菜单不会落后于刚生效的变更。指向套餐外 Sub-App 的菜单节点隐藏；
     * 可加载的 Sub-App 是可见菜单引用、且在 App Registry 中登记了当前环境入口地址的那些。
     */
    public Access of(UUID tenantId, UUID membershipId) {
        Set<String> granted = roles.grantsOf(membershipId).permissions();
        List<MenuNode> menus = withoutActions(permissions.visibleMenus(granted, plans.subAppsOf(tenantId)));
        Set<String> referenced = new TreeSet<>();
        collectApps(menus, referenced);
        return new Access(new ArrayList<>(new TreeSet<>(granted)), menus, subApps.loadable(referenced));
    }

    // Shell App 只需要导航信息，页面操作的组织关系留在授权树中。
    private static List<MenuNode> withoutActions(List<MenuNode> nodes) {
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
     */
    public record Access(List<String> permissions, List<MenuNode> menus, List<LoadableSubApp> subApps) {}
}
