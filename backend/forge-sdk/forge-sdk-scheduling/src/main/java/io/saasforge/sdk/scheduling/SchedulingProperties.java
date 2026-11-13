package io.saasforge.sdk.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant Task 的 IAM 目录配置，由自动配置注册为 Spring Bean。
 *
 * <p>绑定时允许缺省，声明 Tenant Task 后在启动阶段校验必填项；仅有 Global Task 的服务不要求目录配置。
 *
 * @param iamUri IAM 内部地址，不经网关；可引用 forge.manifest.iam-uri
 * @param token 本服务的 manifest 服务令牌，可引用 forge.manifest.token
 */
@ConfigurationProperties("forge.scheduling")
public record SchedulingProperties(String iamUri, String token) {
    void requireTenantTask() {
        require(iamUri, "forge.scheduling.iam-uri");
        require(token, "forge.scheduling.token");
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
    }

    /** 避免令牌被配置诊断日志输出。 */
    @Override
    public String toString() {
        return "SchedulingProperties[iamUri=" + iamUri + "]";
    }
}
