package io.saasforge.platform.plan.model;

import java.util.List;

/**
 * iam 的 Feature Pack 授权树，与 iam {@code GET /internal/permission-tree} 的响应一致。菜单不按 Plan 或 Sub-App 裁剪，
 * 由前端按所选 Sub-App 显示。
 *
 * @param menus 各业务服务的菜单树
 * @param others 可纳入套餐、未挂在菜单上的权限码，按服务、分组、编码排序
 */
public record CatalogPermissionTree(List<Menu> menus, List<CatalogPermission> others) {
    /**
     * 菜单节点。
     *
     * @param code 菜单编码
     * @param name 展示名称
     * @param icon 图标，可为 null
     * @param app 叶子菜单所属的 Sub-App，分组节点为 null
     * @param permission 页面权限，不挂权限码的节点为 null
     * @param actions 页面上的操作权限
     * @param children 子节点
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
     * @param code 权限码
     * @param name 展示名称
     * @param sharedWith 同时挂着该权限码的其他菜单编码
     */
    public record Permission(String code, String name, List<String> sharedWith) {}
}
