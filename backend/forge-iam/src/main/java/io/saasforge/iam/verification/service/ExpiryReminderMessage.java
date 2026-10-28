package io.saasforge.iam.verification.service;

import java.time.OffsetDateTime;

/**
 * 租户试用或订购即将到期的提醒，发给租户的 Owner。
 *
 * @param tenantName 租户名称
 * @param trial 是否为试用到期；否则为订购到期
 * @param expiresAt 到期时间
 */
public record ExpiryReminderMessage(String tenantName, boolean trial, OffsetDateTime expiresAt)
        implements ContactMessage {}
