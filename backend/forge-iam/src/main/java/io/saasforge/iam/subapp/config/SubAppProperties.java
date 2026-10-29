package io.saasforge.iam.subapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sub-App 加载配置，前缀 {@code forge.iam.sub-app}。
 *
 * @param environment 当前部署的环境名，从 App Registry 中各环境的入口地址里选取这一个下发给 Shell App；随环境变化，不设默认值，
 *     漏配时启动失败
 */
@ConfigurationProperties("forge.iam.sub-app")
public record SubAppProperties(String environment) {
    public SubAppProperties {
        if (environment == null || environment.isBlank()) {
            throw new IllegalStateException("forge.iam.sub-app.environment must be set");
        }
    }
}
