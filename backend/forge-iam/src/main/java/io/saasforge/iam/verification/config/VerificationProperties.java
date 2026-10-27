package io.saasforge.iam.verification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 一次性验证码规则，前缀 {@code forge.iam.verification}。
 *
 * @param codeTtl 验证码有效期
 * @param resendInterval 同一目标两次发送的最小间隔，不区分用途
 * @param maxAttempts 错误次数上限，达到即作废
 */
@ConfigurationProperties("forge.iam.verification")
public record VerificationProperties(
        @DefaultValue("5m") Duration codeTtl,
        @DefaultValue("60s") Duration resendInterval,
        @DefaultValue("5") int maxAttempts) {}
