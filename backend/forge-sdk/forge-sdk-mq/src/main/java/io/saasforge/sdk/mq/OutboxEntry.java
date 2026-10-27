package io.saasforge.sdk.mq;

import java.util.UUID;

/**
 * 待投递的 Outbox 事件。
 *
 * @param id 事件 ID，同时作为消费端去重的消息 ID，重发时保持不变
 * @param tenantId 发布时的租户，无租户上下文发布时为 null
 * @param topic 目标 Topic
 * @param tag 事件类型名
 * @param payload JSON 消息体
 * @param attempts 已失败的发送次数
 */
record OutboxEntry(UUID id, UUID tenantId, String topic, String tag, String payload, int attempts) {}
