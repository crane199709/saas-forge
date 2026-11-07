package io.saasforge.sdk.mq;

import java.util.UUID;

/** 仅服务内部使用的重放输入，不得直接作为 HTTP 响应。 */
record DeadEventEntry(
        UUID id,
        String consumerGroup,
        String eventId,
        String originalTenantId,
        String topic,
        String tag,
        byte[] payload) {}
