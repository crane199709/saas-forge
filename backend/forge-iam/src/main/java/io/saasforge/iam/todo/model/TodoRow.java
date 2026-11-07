package io.saasforge.iam.todo.model;

import java.time.Instant;
import java.util.UUID;

/** 待办列表的查询投影，与公开响应保持分离。 */
public record TodoRow(
        UUID id,
        String service,
        String businessKey,
        String title,
        String body,
        String appCode,
        String route,
        Instant occurredAt,
        int occurredAtNanos) {}
