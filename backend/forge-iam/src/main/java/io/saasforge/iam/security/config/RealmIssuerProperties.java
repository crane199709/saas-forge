package io.saasforge.iam.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IAM 签发 Token 使用的两个 Realm 的 issuer，与网关、业务服务共用 {@code forge.security} 前缀（ADR-0003）。
 *
 * @param issuer Tenant Realm 的 issuer
 * @param platform Platform Realm 的 issuer
 */
@ConfigurationProperties("forge.security")
public record RealmIssuerProperties(
        String issuer, @DefaultValue Platform platform) {
    /** 授权服务器的 issuer 为空时会改为按请求地址推断，与下游固定的 issuer 不一致，所以漏配必须拒绝启动。 */
    public RealmIssuerProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("forge.security.issuer must be set");
        }
        if (platform.issuer() == null || platform.issuer().isBlank()) {
            throw new IllegalStateException("forge.security.platform.issuer must be set");
        }
    }

    /**
     * Platform Realm 的签发配置。
     *
     * @param issuer Platform Realm 的 issuer，不能与 Tenant Realm 相同
     */
    public record Platform(String issuer) {}
}
