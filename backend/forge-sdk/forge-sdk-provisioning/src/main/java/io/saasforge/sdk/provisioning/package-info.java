/**
 * Provisioning 与 Tenant Lifecycle 的事件契约，以及租户初始化、租户清理扩展点（规格 5.3、第 9 节）。
 *
 * <p>platform 创建 Tenant 时发布 {@link io.saasforge.sdk.provisioning.TenantCreated}；服务声明
 * {@link io.saasforge.sdk.provisioning.TenantInitializer} 即参与初始化，SDK 负责订阅、事务、回报与按服务重试。
 * 状态变更发布 {@link io.saasforge.sdk.provisioning.TenantStatusChanged}；冷静期结束发布
 * {@link io.saasforge.sdk.provisioning.TenantDeleted}，服务声明 {@link io.saasforge.sdk.provisioning.TenantDataCleaner}
 * 即在此时标记删除本服务的租户数据。租户适用的 Plan 变更时发布 {@link io.saasforge.sdk.provisioning.TenantPlanChanged}；
 * App Registry 中的 Sub-App 变更时发布 {@link io.saasforge.sdk.provisioning.SubAppChanged}。
 */
package io.saasforge.sdk.provisioning;
