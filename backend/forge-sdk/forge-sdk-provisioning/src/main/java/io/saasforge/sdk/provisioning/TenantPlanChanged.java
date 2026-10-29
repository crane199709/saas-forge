package io.saasforge.sdk.provisioning;

import java.util.List;
import java.util.UUID;

/**
 * 租户适用的 Plan 已变更：创建租户、Operator 为租户更换 Plan、修改租户所用 Plan 的内容时，platform 在同一事务中经 Outbox 发布
 * （Topic {@link TenantCreated#TOPIC}），携带该租户的租户上下文。
 *
 * <p>消息带变更后 Plan 的完整内容而不是差量，订阅方直接整体替换本地副本。消息可能乱序或重复送达，订阅方按 {@code version}
 * 只应用比已知更新的变更。
 *
 * @param tenantId 租户 ID
 * @param planId Plan ID
 * @param planCode Plan 编码
 * @param permissions Feature Pack 中可用的 Permission Code，按编码排序
 * @param subApps Feature Pack 中可用的 Sub-App 编码，按编码排序
 * @param memberQuota 成员数上限，为 null 表示不限
 * @param storageQuotaMb 存储容量上限（MiB），为 null 表示不限
 * @param version 该租户的 Plan 版本，每次变更加 1，创建租户时为 1
 */
public record TenantPlanChanged(
        UUID tenantId,
        UUID planId,
        String planCode,
        List<String> permissions,
        List<String> subApps,
        Integer memberQuota,
        Long storageQuotaMb,
        long version) {
    public TenantPlanChanged {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        subApps = subApps == null ? List.of() : List.copyOf(subApps);
    }
}
