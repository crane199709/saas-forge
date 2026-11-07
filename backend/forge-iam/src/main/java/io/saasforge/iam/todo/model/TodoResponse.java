package io.saasforge.iam.todo.model;

import io.saasforge.sdk.notification.NotificationLink;
import java.time.Instant;
import java.util.UUID;

/** 当前成员可处理的待办。 */
public record TodoResponse(
        UUID id,
        String service,
        String businessKey,
        String title,
        String body,
        NotificationLink link,
        Instant occurredAt) {
    public static TodoResponse from(TodoRow row) {
        return new TodoResponse(
                row.id(),
                row.service(),
                row.businessKey(),
                row.title(),
                row.body(),
                row.appCode() == null ? null : new NotificationLink(row.appCode(), row.route()),
                row.occurredAt().plusNanos(row.occurredAtNanos()));
    }
}
