package io.saasforge.iam.invitation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 邀请配置，前缀 {@code forge.iam.invitation}。
 *
 * @param acceptUrl tenant-app 接受邀请的页面地址，邀请令牌以 {@code invitation} 参数附在其后
 * @param ttl 邀请有效期
 */
@ConfigurationProperties("forge.iam.invitation")
public record InvitationProperties(
        String acceptUrl, @DefaultValue("7d") Duration ttl) {
    // 地址随环境变化，漏配时邀请链接无法打开，启动即失败。
    public InvitationProperties {
        if (acceptUrl == null || acceptUrl.isBlank()) {
            throw new IllegalStateException("forge.iam.invitation.accept-url must be set");
        }
    }
}
