/**
 * Provisioning 事件契约与租户初始化扩展点（规格第 9 节）。
 *
 * <p>platform 创建 Tenant 时发布 {@link io.saasforge.sdk.provisioning.TenantCreated}；服务声明
 * {@link io.saasforge.sdk.provisioning.TenantInitializer} 即参与初始化，SDK 负责订阅、事务、回报与按服务重试。
 */
package io.saasforge.sdk.provisioning;
