package io.saasforge.iam.role.model;

import io.saasforge.iam.permission.model.PermissionRow;
import java.util.List;

/**
 * 菜单授权树：菜单节点挂可勾选的 Permission Code，未挂在菜单上的权限码放在 {@code others}。
 *
 * @param menus 裁剪后的菜单树
 * @param others 范围内、未出现在菜单树上的权限码，按服务、分组、编码排序
 */
public record PermissionTreeResponse(List<Menu> menus, List<PermissionRow> others) {
    /**
     * 菜单节点。
     *
     * @param app 叶子菜单所属的 Sub-App，分组节点为 null
     * @param permission 页面权限，不挂权限码的节点为 null
     * @param actions 页面上的操作权限
     */
    public record Menu(
            String code,
            String name,
            String icon,
            String app,
            Permission permission,
            List<Permission> actions,
            List<Menu> children) {}

    /**
     * 节点上可勾选的权限码。
     *
     * @param sharedWith 同时挂着该权限码的其他菜单编码
     */
    public record Permission(String code, String name, List<String> sharedWith) {}
}
