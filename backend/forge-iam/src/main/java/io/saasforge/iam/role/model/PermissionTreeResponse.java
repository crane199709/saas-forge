package io.saasforge.iam.role.model;

import io.saasforge.iam.permission.model.PermissionRow;
import java.util.List;

public record PermissionTreeResponse(List<Menu> menus, List<PermissionRow> others) {
    public record Menu(
            String code,
            String name,
            String icon,
            Permission permission,
            List<Permission> actions,
            List<Menu> children) {}

    public record Permission(String code, String name, List<String> sharedWith) {}
}
