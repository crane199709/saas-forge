package io.saasforge.iam.access.service;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 当前 Membership 的权限码与按权限裁剪后的菜单树，供 Shell App 生成路由、导航与 {@code v-perm}。 */
@Service
public class AccessService {
    private final RoleService roles;
    private final PermissionService permissions;

    AccessService(RoleService roles, PermissionService permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    /** 按数据库中的当前角色计算，而不是 Token 签发时发布的明细，菜单不会落后于刚生效的变更。 */
    public Access of(UUID membershipId) {
        Set<String> granted = roles.grantsOf(membershipId).permissions();
        return new Access(new ArrayList<>(new TreeSet<>(granted)), visible(permissions.currentMenus(), granted));
    }

    // 缺少权限的节点连同子树一起隐藏；子节点全部被隐藏的分组节点也不再展示。
    private static List<MenuNode> visible(List<MenuNode> nodes, Set<String> granted) {
        List<MenuNode> result = new ArrayList<>();
        for (MenuNode node : nodes) {
            if (node.permission() != null && !granted.contains(node.permission())) continue;
            if (node.children().isEmpty()) {
                result.add(node);
                continue;
            }
            List<MenuNode> children = visible(node.children(), granted);
            if (!children.isEmpty()) result.add(node.withChildren(children));
        }
        return result;
    }

    /**
     * @param permissions 权限码，按编码排序
     * @param menus 可见菜单树
     */
    public record Access(List<String> permissions, List<MenuNode> menus) {}
}
