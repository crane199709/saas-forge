package io.saasforge.sdk.provisioning;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 租户的试用或订购即将到期，platform 在到期前的提醒窗口内发布一次（Topic {@link TenantCreated#TOPIC}），携带该租户的租户上下文。
 * iam 据此经邮件/短信 SPI 提醒 Owner。
 *
 * @param tenantId 租户 ID
 * @param name 租户名称
 * @param status 当前状态，{@code trial} 表示试用到期，{@code active} 表示订购到期
 * @param expiresAt 到期时间
 */
public record TenantExpiryApproached(UUID tenantId, String name, String status, OffsetDateTime expiresAt) {}
