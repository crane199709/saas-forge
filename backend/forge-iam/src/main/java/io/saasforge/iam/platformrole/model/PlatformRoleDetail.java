package io.saasforge.iam.platformrole.model;

import io.saasforge.iam.role.model.DataPolicy;
import java.util.List;
import java.util.UUID;

/**
 * 平台角色及其授权。
 *
 * @param permissions 授予的有效平台 Permission Code，按编码排序；超级管理员不落授权记录，为空列表
 * @param dataPolicies 各平台 Data Resource 上的策略，按资源编码排序；超级管理员对全部资源为「全部」，不落记录，为空列表
 */
public record PlatformRoleDetail(
        UUID id,
        String code,
        String name,
        boolean builtin,
        long operatorCount,
        List<String> permissions,
        List<DataPolicy> dataPolicies) {
    public static PlatformRoleDetail of(PlatformRoleRow row, List<String> permissions, List<DataPolicy> dataPolicies) {
        return new PlatformRoleDetail(
                row.id(), row.code(), row.name(), row.builtin(), row.operatorCount(), permissions, dataPolicies);
    }
}
