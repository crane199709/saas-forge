package io.saasforge.platform.onboarding.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 真实网关探测的环境地址，不接受业务服务 lb 地址替代网关。 */
@ConfigurationProperties("forge.platform.onboarding")
public record OnboardingProperties(URI gatewayUri) {
    public OnboardingProperties {
        if (gatewayUri == null
                || !("http".equals(gatewayUri.getScheme()) || "https".equals(gatewayUri.getScheme()))
                || gatewayUri.getHost() == null
                || gatewayUri.getUserInfo() != null
                || gatewayUri.getQuery() != null
                || gatewayUri.getFragment() != null
                || (gatewayUri.getPath() != null
                        && !gatewayUri.getPath().isEmpty()
                        && !gatewayUri.getPath().equals("/"))) {
            throw new IllegalStateException("forge.platform.onboarding.gateway-uri must be an HTTP gateway origin");
        }
    }
}
