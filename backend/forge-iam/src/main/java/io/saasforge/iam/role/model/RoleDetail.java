package io.saasforge.iam.role.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 角色及其授权。
 *
 * @param permissions 授予的有效 Permission Code，按编码排序；预置角色不落授权记录，为空列表
 * @param dataPolicies 各 Data Resource 上的策略，按资源编码排序；预置角色对全部资源为「全部」，不落记录，为空列表
 */
public record RoleDetail(
        UUID id,
        String code,
        String name,
        boolean builtin,
        boolean owner,
        long memberCount,
        List<String> permissions,
        List<DataPolicy> dataPolicies,
        Instant updatedAt,
        UUID updatedBy) {
    public static RoleDetail of(RoleRow row, List<String> permissions, List<DataPolicy> dataPolicies) {
        return new RoleDetail(
                row.id(),
                row.code(),
                row.name(),
                row.builtin(),
                row.owner(),
                row.memberCount(),
                permissions,
                dataPolicies,
                row.updatedAt(),
                row.updatedBy());
    }
}
