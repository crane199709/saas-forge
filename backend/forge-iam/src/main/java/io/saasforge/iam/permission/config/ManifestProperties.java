package io.saasforge.iam.permission.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resource Manifest 上报配置，前缀 {@code forge.iam.manifest}。
 *
 * @param tokens 服务名到上报令牌的映射；令牌随环境变化，不设默认值，漏配时启动失败
 */
@ConfigurationProperties("forge.iam.manifest")
public record ManifestProperties(Map<String, String> tokens) {
    /** 没有任何服务能上报清单时功能权限无从配置，必须显式配置。 */
    public ManifestProperties {
        if (tokens == null || tokens.isEmpty())
            throw new IllegalStateException("forge.iam.manifest.tokens must be set");
        tokens.forEach((service, token) -> {
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("forge.iam.manifest.tokens." + service + " must be set");
            }
        });
        tokens = Map.copyOf(tokens);
    }

    @Override
    public String toString() {
        return "ManifestProperties[services=" + tokens.keySet() + "]";
    }
}
