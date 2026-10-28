package io.saasforge.sdk.provisioning;

/**
 * 租户初始化扩展点：服务声明此类型的 Bean，即在租户创建后与 Operator 重试时为新租户准备本服务的默认数据（如预置角色模板、字典）。
 *
 * <p>SDK 调用前已恢复新租户的租户上下文并开启事务，数据访问照常按租户隔离。同一服务的全部初始化器在同一个保存点内执行：
 * 任一抛出异常则全部回滚，并回报失败；全部返回则回报成功，回报与初始化数据同一事务提交。
 *
 * <p>实现必须幂等：重试会再次调用，之前可能已部分或全部完成。
 */
public interface TenantInitializer {
    /**
     * 为新租户初始化本服务数据。
     *
     * @param tenant 租户创建事件
     */
    void initialize(TenantCreated tenant);
}
