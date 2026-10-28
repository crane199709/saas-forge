package io.saasforge.sdk.provisioning;

/**
 * 租户清理扩展点：服务声明此类型的 Bean，即在 {@link TenantDeleted} 时把本服务中该租户的数据标记删除（ADR-0016）。
 *
 * <p>SDK 调用前已在 {@code CurrentTenant.run(tenantId, ...)} 中恢复该租户的上下文并开启事务，且已声明
 * {@link io.saasforge.sdk.tenant.TenantWriteGuard#exempt(Runnable) 写入保护例外}：此时租户处于删除状态，普通写入会被拒绝。
 * 实现在 mapper XML 中批量执行 {@code UPDATE ... SET deleted = true, updated_at = now(), updated_by = NULL WHERE NOT deleted}，
 * 租户条件照常由 SDK 注入，不做物理删除。
 *
 * <p>同一服务的全部清理器在同一事务中执行，任一抛出异常则全部回滚，消息由 RocketMQ 稍后重投；实现必须幂等。
 */
public interface TenantDataCleaner {
    /**
     * 把本服务中该租户的数据标记删除。
     *
     * @param event 租户删除事件
     */
    void clean(TenantDeleted event);
}
