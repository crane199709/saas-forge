package io.saasforge.sdk.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务专属运维令牌；平台为每个服务配置相同的值，不接受用户 JWT。 */
@ConfigurationProperties("forge.ops")
public record OpsProperties(String token) {
    /** 引入运维能力的服务必须显式配置令牌。 */
    public OpsProperties {
        if (token == null || token.isBlank() || token.contains("${"))
            throw new IllegalStateException("forge.ops.token must be set");
    }

    @Override
    public String toString() {
        return "OpsProperties[token=******]";
    }
}
