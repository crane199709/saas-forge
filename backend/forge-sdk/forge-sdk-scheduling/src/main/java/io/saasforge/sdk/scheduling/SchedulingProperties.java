package io.saasforge.sdk.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant Task 的 IAM 目录配置；仅存在 Tenant Task 时绑定并校验。
 *
 * @param iamUri IAM 内部地址，不经网关；可引用 forge.manifest.iam-uri
 * @param token 本服务的 manifest 服务令牌，可引用 forge.manifest.token
 */
@ConfigurationProperties("forge.scheduling")
public record SchedulingProperties(String iamUri, String token) {
    /** 地址与令牌随环境变化，不设默认值，声明 Tenant Task 后漏配即启动失败。 */
    public SchedulingProperties {
        require(iamUri, "forge.scheduling.iam-uri");
        require(token, "forge.scheduling.token");
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) throw new IllegalStateException(property + " must be set");
    }

    /** 避免令牌被配置诊断日志输出。 */
    @Override
    public String toString() {
        return "SchedulingProperties[iamUri=" + iamUri + "]";
    }
}
