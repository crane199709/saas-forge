package io.saasforge.gateway.routing.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("forge.gateway")
public record GatewayServicesProperties(List<Service> services) {
    public GatewayServicesProperties {
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("forge.gateway.services must not be empty");
        }
        services = List.copyOf(services);
        var prefixes = new HashSet<String>();
        for (Service service : services) {
            if (!prefixes.add(service.prefix())) {
                throw new IllegalArgumentException("Duplicate gateway service prefix: " + service.prefix());
            }
        }
    }

    public record Service(String prefix, Realm realm) {
        public Service {
            // 限定为单个路径段，避免通配符与重叠前缀改变安全规则的匹配顺序。
            if (prefix == null || !prefix.matches("/[a-z][a-z0-9-]*")) {
                throw new IllegalArgumentException(
                        "Gateway service prefix must be a single path segment, e.g. /orders");
            }
            // 内建公开端点先于服务规则匹配，服务不得占用这些前缀。
            if (Set.of("/actuator", "/v3", "/swagger-ui", "/webjars").contains(prefix)) {
                throw new IllegalArgumentException("Reserved gateway service prefix: " + prefix);
            }
            if (realm == null) {
                throw new IllegalArgumentException("Gateway service realm is required");
            }
            if (prefix.equals("/iam") && realm != Realm.TENANT) {
                throw new IllegalArgumentException("IAM defaults to tenant realm; /iam/platform uses platform realm");
            }
        }
    }

    public enum Realm {
        TENANT,
        PLATFORM
    }
}
