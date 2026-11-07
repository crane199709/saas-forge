package io.saasforge.sdk.notification;

import java.util.List;
import java.util.UUID;

/** 观察 Outbox 传输边界，不替代或 mock SDK 内部的发布器。 */
interface PublishedEvents {
    List<Event> all();

    record Event(UUID tenantId, String topic, String tag, String payload) {}
}
