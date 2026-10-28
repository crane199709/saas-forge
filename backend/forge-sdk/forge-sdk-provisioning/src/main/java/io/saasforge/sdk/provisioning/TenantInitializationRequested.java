package io.saasforge.sdk.provisioning;

/**
 * 请求某个服务重新初始化租户，platform 在 Operator 重试失败的初始化时发布，与 {@link TenantCreated} 同一 Topic。
 *
 * <p>所有服务都会收到，只有 {@code service} 与自身 {@code spring.application.name} 相同的服务执行初始化。
 *
 * @param service 要重试的服务名
 * @param tenant 原租户创建事件的内容
 */
public record TenantInitializationRequested(String service, TenantCreated tenant) {}
