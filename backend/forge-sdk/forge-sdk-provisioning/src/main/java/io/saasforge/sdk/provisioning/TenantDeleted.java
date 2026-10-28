package io.saasforge.sdk.provisioning;

import java.util.UUID;

/**
 * 租户的 Cooling-off Period 已结束，删除不可再撤回（规格 5.3、ADR-0016）。platform 在冷静期到期后经 Outbox 发布
 * （Topic {@link TenantCreated#TOPIC}），携带该租户的租户上下文。
 *
 * <p>各服务实现 {@link TenantDataCleaner} 即可据此把本服务中该租户的数据标记删除，不需要直接订阅本事件。
 *
 * @param tenantId 被删除的租户 ID
 */
public record TenantDeleted(UUID tenantId) {}
