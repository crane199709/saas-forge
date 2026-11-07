package io.saasforge.sdk.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification 已请求发送，IAM 按目标创建站内收件记录。
 * @param id Notification ID，所有收件人共享，用于幂等写入
 * @param tenantId 所属 Tenant
 * @param service 来源 Business Service
 * @param target 接收目标
 * @param title 纯文本标题，最长 200 字符
 * @param body 纯文本正文，最长 10000 字符
 * @param link 站内链接，可为 null
 * @param occurredAt 发生时间
 */
public record NotificationSent(
        UUID id,
        UUID tenantId,
        String service,
        NotificationTarget target,
        String title,
        String body,
        NotificationLink link,
        Instant occurredAt) {
    /** 验证跨服务事件所需的租户、内容和目标，直接反序列化时同样执行。 */
    public NotificationSent {
        NotificationValidation.required(id, "id");
        NotificationValidation.required(tenantId, "tenantId");
        NotificationValidation.text(service, "service", 255);
        NotificationValidation.required(target, "target");
        NotificationValidation.text(title, "title", 200);
        NotificationValidation.text(body, "body", 10000);
        NotificationValidation.required(occurredAt, "occurredAt");
    }

    /** Notification 与 Todo 共用的 RocketMQ Topic。 */
    public static final String TOPIC = "forge-notification";
}
