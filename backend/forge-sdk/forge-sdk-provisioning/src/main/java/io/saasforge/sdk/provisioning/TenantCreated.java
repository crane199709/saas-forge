package io.saasforge.sdk.provisioning;

import java.util.UUID;

/**
 * 租户已创建事件，platform 在创建 Tenant 的同一事务中经 Outbox 发布，携带新租户的租户上下文。
 *
 * <p>各服务实现 {@link TenantInitializer} 即可据此初始化本服务数据，不需要直接订阅本事件。
 *
 * @param tenantId 新租户 ID
 * @param name 租户名称
 * @param adminContact 初始管理员的邮箱或手机号，含 {@code @} 的是邮箱
 */
public record TenantCreated(UUID tenantId, String name, String adminContact) {
    /** Provisioning 相关事件所在的 Topic。 */
    public static final String TOPIC = "forge-tenant";
}
