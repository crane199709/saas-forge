package io.saasforge.sdk.mq;

import java.time.Instant;
import java.util.UUID;

/** Dead Event 的运维元数据；原始消息体与原租户头不属于接口契约。 */
public record DeadEventResponse(
        UUID id,
        String consumerGroup,
        String eventId,
        UUID tenantId,
        String topic,
        String tag,
        int deliveryAttempt,
        String lastError,
        String status,
        Instant createdAt,
        Instant resolvedAt,
        String resolutionReason) {}
