package io.saasforge.sdk.provisioning;

import java.util.UUID;

/**
 * 服务回报一次租户初始化的结果，由 SDK 在初始化后发布，与 {@link TenantCreated} 同一 Topic，platform 据此展示初始化状态。
 *
 * @param tenantId 租户 ID
 * @param service 回报的服务名，即其 {@code spring.application.name}
 * @param succeeded 是否成功
 * @param error 失败原因，便于排障；成功时为 null
 */
public record TenantInitializationReported(UUID tenantId, String service, boolean succeeded, String error) {}
