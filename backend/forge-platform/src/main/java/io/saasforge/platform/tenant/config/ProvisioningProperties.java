package io.saasforge.platform.tenant.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provisioning 配置，前缀 {@code forge.platform.provisioning}。
 *
 * @param services 创建租户时预建初始化状态的服务名（各服务的 {@code spring.application.name}）
 */
@ConfigurationProperties("forge.platform.provisioning")
public record ProvisioningProperties(List<String> services) {
    public ProvisioningProperties {
        services = services == null ? List.of() : List.copyOf(services);
    }
}
