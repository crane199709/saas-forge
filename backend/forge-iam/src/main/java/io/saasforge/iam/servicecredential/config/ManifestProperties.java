package io.saasforge.iam.servicecredential.config;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resource Manifest 上报配置，前缀 {@code forge.iam.manifest}。
 *
 * @param tokens 服务名到上报令牌的映射；已弃用的引导通道，可为空；生产凭证通过管理入口登记
 */
@ConfigurationProperties("forge.iam.manifest")
public record ManifestProperties(Map<String, String> tokens) {
    /** 未配置引导通道时仅接受数据库凭证。 */
    public ManifestProperties {
        if (tokens == null) {
            tokens = Map.of();
        }
        tokens = tokens.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public String toString() {
        return "ManifestProperties[services=" + tokens.keySet() + "]";
    }
}
