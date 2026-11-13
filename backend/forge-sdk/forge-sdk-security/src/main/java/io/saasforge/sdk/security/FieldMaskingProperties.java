package io.saasforge.sdk.security;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 仅启用脱敏运行时的服务需要提供的 IAM 配置，不为地址或凭证设置环境默认值。
 *
 * @param iamUri IAM 内部 HTTP(S) 地址，不经网关
 * @param token 本服务的 Service Credential，服务身份由 IAM 从凭证推导
 */
@ConfigurationProperties("forge.field-masking")
public record FieldMaskingProperties(String iamUri, String token) {
    /** 校验必填配置与 HTTP 地址，错误信息不包含配置值。 */
    public FieldMaskingProperties {
        require(iamUri, "forge.field-masking.iam-uri");
        require(token, "forge.field-masking.token");
        URI uri;
        try {
            uri = URI.create(iamUri);
        } catch (IllegalArgumentException error) {
            throw invalidUri();
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw invalidUri();
        }
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
    }

    private static IllegalStateException invalidUri() {
        return new IllegalStateException("forge.field-masking.iam-uri must be an HTTP(S) address");
    }

    /** 配置可能写入启动日志，禁止输出凭证。 */
    @Override
    public String toString() {
        return "FieldMaskingProperties[token=[redacted]]";
    }
}
