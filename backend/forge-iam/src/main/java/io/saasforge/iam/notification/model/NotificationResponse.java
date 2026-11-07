package io.saasforge.iam.notification.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.saasforge.sdk.notification.NotificationLink;
import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String service,
        String title,
        String body,
        @JsonIgnore String appCode,
        @JsonIgnore String route,
        OffsetDateTime occurredAt,
        OffsetDateTime readAt) {
    @JsonProperty("read")
    public boolean read() {
        return readAt != null;
    }

    @JsonProperty("link")
    public NotificationLink link() {
        return appCode == null ? null : new NotificationLink(appCode, route);
    }
}
