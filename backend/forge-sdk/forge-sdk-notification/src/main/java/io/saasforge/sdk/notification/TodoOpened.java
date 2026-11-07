package io.saasforge.sdk.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Todo 已开启；IAM 按 Tenant、service、businessKey 幂等维护，并只采用发生时间更晚的状态。
 * @param tenantId 所属 Tenant
 * @param service 来源 Business Service
 * @param businessKey 本服务内唯一的业务键，最长 255 字符
 * @param target 处理目标，Permission Code 在读取时判断
 * @param title 纯文本标题，最长 200 字符
 * @param body 纯文本正文，最长 10000 字符
 * @param link 站内链接，可为 null；Sub-App 不在 Plan 内时隐藏
 * @param occurredAt 业务状态发生时间，不能用重试时间替换
 */
public record TodoOpened(
        UUID tenantId,
        String service,
        String businessKey,
        NotificationTarget target,
        String title,
        String body,
        NotificationLink link,
        Instant occurredAt) {
    /** 校验业务键、内容与发生时间，避免产生消费端无法应用的事件。 */
    public TodoOpened {
        NotificationValidation.required(tenantId, "tenantId");
        NotificationValidation.text(service, "service", 255);
        NotificationValidation.text(businessKey, "businessKey", 255);
        NotificationValidation.required(target, "target");
        NotificationValidation.text(title, "title", 200);
        NotificationValidation.text(body, "body", 10000);
        NotificationValidation.required(occurredAt, "occurredAt");
    }

    /** Todo 与 Notification 共用的 RocketMQ Topic。 */
    public static final String TOPIC = NotificationSent.TOPIC;
}
