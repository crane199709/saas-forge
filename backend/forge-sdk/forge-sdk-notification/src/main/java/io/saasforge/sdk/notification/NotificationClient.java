package io.saasforge.sdk.notification;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.mq.EventPublisher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在业务事务中发布 Notification 和 Todo；事件与业务数据同提交、同回滚，经 Outbox 送达 IAM。
 * 租户取自 CurrentTenant，来源服务取自 spring.application.name。
 */
public final class NotificationClient {
    private final EventPublisher publisher;
    private final String service;

    NotificationClient(EventPublisher publisher, String service) {
        this.publisher = publisher;
        this.service = NotificationValidation.text(service, "spring.application.name", 255);
    }

    /**
     * 发送 Notification；权限目标在 IAM 创建通知时展开为当时的持有者。
     * @param target 接收目标
     * @param title 纯文本标题，最长 200 字符
     * @param body 纯文本正文，最长 10000 字符
     * @param link 站内链接，可为 null
     * @return Notification ID
     * @throws IllegalStateException 不在业务事务中或未绑定 Tenant
     * @throws IllegalArgumentException 内容或目标无效
     */
    public UUID send(NotificationTarget target, String title, String body, NotificationLink link) {
        requireTransaction();
        UUID id = UuidV7.generate();
        publisher.publish(
                NotificationSent.TOPIC,
                new NotificationSent(id, CurrentTenant.require(), service, target, title, body, link, Instant.now()));
        return id;
    }

    /**
     * 开启或重新开启 Todo；同一服务业务键只在新发生时间更晚时更新。
     * @param businessKey 本服务内唯一的业务键，最长 255 字符
     * @param target 处理目标，Permission Code 在读取时判断
     * @param title 纯文本标题，最长 200 字符
     * @param body 纯文本正文，最长 10000 字符
     * @param link 站内链接，可为 null
     * @param occurredAt 业务状态实际发生时间，重试必须沿用原值
     * @throws IllegalStateException 不在业务事务中或未绑定 Tenant
     * @throws IllegalArgumentException 业务键、内容、目标或发生时间无效
     */
    public void openTodo(
            String businessKey,
            NotificationTarget target,
            String title,
            String body,
            NotificationLink link,
            Instant occurredAt) {
        requireTransaction();
        publisher.publish(
                TodoOpened.TOPIC,
                new TodoOpened(CurrentTenant.require(), service, businessKey, target, title, body, link, occurredAt));
    }

    /**
     * 关闭本服务业务键对应的 Todo，所有处理人同时关闭。
     * @param businessKey 本服务内唯一的业务键，最长 255 字符
     * @param occurredAt 业务状态实际发生时间，重试必须沿用原值
     * @throws IllegalStateException 不在业务事务中或未绑定 Tenant
     * @throws IllegalArgumentException 业务键或发生时间无效
     */
    public void closeTodo(String businessKey, Instant occurredAt) {
        requireTransaction();
        publisher.publish(TodoClosed.TOPIC, new TodoClosed(CurrentTenant.require(), service, businessKey, occurredAt));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification and Todo must be published inside a business transaction");
        }
    }
}
