package io.saasforge.sdk.notification;

import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.mq.MqAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 为 Business Service 提供与业务事务绑定的 Notification 和 Todo 发布入口。 */
@AutoConfiguration(after = MqAutoConfiguration.class)
public final class NotificationAutoConfiguration {
    @Bean
    NotificationClient notificationClient(EventPublisher publisher, Environment environment) {
        return new NotificationClient(publisher, environment.getRequiredProperty("spring.application.name"));
    }
}
