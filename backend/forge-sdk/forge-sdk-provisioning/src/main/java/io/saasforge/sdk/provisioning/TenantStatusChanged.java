package io.saasforge.sdk.provisioning;

import java.util.UUID;

/**
 * Tenant Lifecycle 状态已变更，platform 在变更状态的同一事务中经 Outbox 发布（Topic {@link TenantCreated#TOPIC}），携带该租户的租户上下文。
 *
 * <p>iam 据此更新租户副本与 Redis 中的租户状态，网关与 SDK 的写入保护读取该状态。消息可能乱序或重复送达，
 * 订阅方按 {@code version} 只应用比已知更新的变更。
 *
 * @param tenantId 租户 ID
 * @param name 租户名称，供尚未登记该租户的订阅方补登记
 * @param previousStatus 变更前的状态
 * @param status 变更后的状态
 * @param version 该租户的状态版本，每次变更加 1，创建时为 0
 */
public record TenantStatusChanged(UUID tenantId, String name, String previousStatus, String status, long version) {}
