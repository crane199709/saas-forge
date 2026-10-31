package io.saasforge.iam.role.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.plan.service.PlanService;
import io.saasforge.iam.role.model.PermissionTreeResponse;
import io.saasforge.iam.role.model.PermissionTreeResponse.Menu;
import io.saasforge.iam.role.model.PermissionTreeResponse.Permission;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PermissionTreeService {
    private final RoleService roles;
    private final PermissionService permissions;
    private final PlanService plans;

    PermissionTreeService(RoleService roles, PermissionService permissions, PlanService plans) {
        this.plans = plans;
        this.roles = roles;
        this.permissions = permissions;
    }

    public PermissionTreeResponse of(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ROLE_MANAGE);
        List<PermissionRow> grantable = roles.grantablePermissions(actor.tenantId());
        Map<String, PermissionRow> byCode = new LinkedHashMap<>();
        grantable.forEach(row -> byCode.put(row.code(), row));
        List<MenuNode> menus = visible(permissions.currentMenus(), byCode.keySet(), plans.subAppsOf(actor.tenantId()));
        Map<String, Set<String>> usages = new LinkedHashMap<>();
        collect(menus, usages);
        return new PermissionTreeResponse(
                render(menus, byCode, usages),
                grantable.stream()
                        .filter(row -> !usages.containsKey(row.code()))
                        .toList());
    }

    // 与导航采用相同的节点裁剪规则，但依据租户可授予权限，不能按管理员自身授权缩小选项。
    private static List<MenuNode> visible(List<MenuNode> nodes, Set<String> grantable, Optional<Set<String>> plan) {
        List<MenuNode> result = new ArrayList<>();
        for (MenuNode node : nodes) {
            if (node.permission() != null && !grantable.contains(node.permission())) continue;
            if (node.children().isEmpty()) {
                if (plan.isEmpty() || plan.get().contains(node.app())) {
                    result.add(new MenuNode(
                            node.code(),
                            node.name(),
                            node.icon(),
                            node.app(),
                            node.path(),
                            node.permission(),
                            List.of(),
                            node.actions().stream().filter(grantable::contains).toList()));
                }
                continue;
            }
            List<MenuNode> children = visible(node.children(), grantable, plan);
            if (!children.isEmpty()) result.add(node.withChildren(children));
        }
        return result;
    }

    private static void collect(List<MenuNode> nodes, Map<String, Set<String>> usages) {
        for (MenuNode node : nodes) {
            if (node.permission() != null)
                usages.computeIfAbsent(node.permission(), key -> new LinkedHashSet<>())
                        .add(node.code());
            for (String action : node.actions())
                usages.computeIfAbsent(action, key -> new LinkedHashSet<>()).add(node.code());
            collect(node.children(), usages);
        }
    }

    private static List<Menu> render(
            List<MenuNode> nodes, Map<String, PermissionRow> byCode, Map<String, Set<String>> usages) {
        List<Menu> result = new ArrayList<>();
        for (MenuNode node : nodes) {
            result.add(new Menu(
                    node.code(),
                    node.name(),
                    node.icon(),
                    node.permission() == null ? null : option(node.permission(), node.code(), byCode, usages),
                    node.actions().stream()
                            .map(code -> option(code, node.code(), byCode, usages))
                            .toList(),
                    render(node.children(), byCode, usages)));
        }
        return result;
    }

    private static Permission option(
            String code, String menu, Map<String, PermissionRow> byCode, Map<String, Set<String>> usages) {
        return new Permission(
                code,
                byCode.get(code).name(),
                usages.get(code).stream().filter(other -> !other.equals(menu)).toList());
    }
}
