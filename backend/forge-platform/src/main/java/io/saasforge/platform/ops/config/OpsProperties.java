package io.saasforge.platform.ops.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("forge.platform.ops")
public record OpsProperties(Map<String, String> tokens) {
    public OpsProperties {
        if (tokens == null || tokens.isEmpty())
            throw new IllegalStateException("forge.platform.ops.tokens must be set");
        tokens.forEach((service, token) -> {
            if (!service.matches("[a-z0-9][a-z0-9-]*") || token == null || token.isBlank() || token.contains("${"))
                throw new IllegalStateException("forge.platform.ops.tokens requires a service name and nonblank token");
        });
        tokens = Map.copyOf(tokens);
    }

    @Override
    public String toString() {
        return "OpsProperties[services=" + tokens.keySet() + "]";
    }
}
