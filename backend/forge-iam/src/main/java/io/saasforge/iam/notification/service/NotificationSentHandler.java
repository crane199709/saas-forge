package io.saasforge.iam.notification.service;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.notification.NotificationSent;
import org.springframework.stereotype.Component;

@Component
class NotificationSentHandler implements EventHandler<NotificationSent> {
    private final NotificationService notifications;

    NotificationSentHandler(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public String topic() {
        return NotificationSent.TOPIC;
    }

    @Override
    public Class<NotificationSent> eventType() {
        return NotificationSent.class;
    }

    @Override
    public void handle(NotificationSent event) {
        notifications.store(event);
    }
}
