package io.saasforge.sdk.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Todo 已关闭，对所有处理人同时生效；关闭先到时 IAM 仍保存状态，防止迟到的旧开启重开待办。
 * @param tenantId 所属 Tenant
 * @param service 来源 Business Service
 * @param businessKey 本服务内唯一的业务键，最长 255 字符
 * @param occurredAt 业务状态发生时间，不能用重试时间替换
 */
public record TodoClosed(UUID tenantId, String service, String businessKey, Instant occurredAt) {
    /** 校验关闭状态的幂等键和发生时间。 */
    public TodoClosed {
        NotificationValidation.required(tenantId, "tenantId");
        NotificationValidation.text(service, "service", 255);
        NotificationValidation.text(businessKey, "businessKey", 255);
        NotificationValidation.required(occurredAt, "occurredAt");
    }

    /** Todo 与 Notification 共用的 RocketMQ Topic。 */
    public static final String TOPIC = NotificationSent.TOPIC;
}
