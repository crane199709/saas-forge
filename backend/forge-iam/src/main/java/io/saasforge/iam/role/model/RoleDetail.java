package io.saasforge.iam.role.model;

import java.util.List;
import java.util.UUID;

/**
 * 角色及其授权。
 *
 * @param permissions 授予的有效 Permission Code，按编码排序；预置角色不落授权记录，为空列表
 */
public record RoleDetail(
        UUID id, String code, String name, boolean builtin, boolean owner, long memberCount, List<String> permissions) {
    public static RoleDetail of(RoleRow row, List<String> permissions) {
        return new RoleDetail(
                row.id(), row.code(), row.name(), row.builtin(), row.owner(), row.memberCount(), permissions);
    }
}
