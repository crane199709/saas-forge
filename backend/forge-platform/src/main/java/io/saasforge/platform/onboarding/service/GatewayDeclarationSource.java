package io.saasforge.platform.onboarding.service;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import io.saasforge.platform.onboarding.model.OnboardingDiagnostics.ConfigurationEvidence;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

/** 读取与网关相同 Nacos dataId；客户端可能提供 snapshot/failover，不能证明网关运行时已加载。 */
@Service
public class GatewayDeclarationSource {
    private final ConfigService config;
    private final String group;

    @Autowired
    public GatewayDeclarationSource(ObjectProvider<NacosConfigManager> manager) {
        var value = manager.getIfAvailable();
        this.config = value == null ? null : value.getConfigService();
        this.group = value == null
                ? "DEFAULT_GROUP"
                : value.getNacosConfigProperties().getGroup();
    }
    /** ConfigService 是外部配置系统边界；测试可提供受控源。 */
    public GatewayDeclarationSource(ConfigService config, String group) {
        this.config = config;
        this.group = group;
    }

    public Snapshot read() {
        Instant checkedAt = Instant.now();
        try {
            if (config == null || !"UP".equals(config.getServerStatus())) {
                return unknown(checkedAt, "NACOS_UNAVAILABLE");
            }
            String yaml = config.getConfig("forge-gateway.yaml", group, 2000);
            if (!"UP".equals(config.getServerStatus())) {
                return unknown(checkedAt, "NACOS_UNAVAILABLE");
            }
            if (yaml == null || yaml.isBlank()) {
                return unknown(checkedAt, "GATEWAY_CONFIG_MISSING");
            }
            var factory = new YamlMapFactoryBean();
            factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> root = factory.getObject();
            Map<String, String> realms = new HashMap<>();
            for (Object item : list(at(root, "forge", "gateway", "services"))) {
                if (item instanceof Map<?, ?> m
                        && m.get("prefix") instanceof String prefix
                        && m.get("realm") instanceof String realm) {
                    realms.put(prefix, realm);
                }
            }
            Map<String, List<Route>> declarations = new TreeMap<>();
            for (Object item : list(at(root, "spring", "cloud", "gateway", "server", "webflux", "routes"))) {
                if (!(item instanceof Map<?, ?> route)
                        || !(route.get("uri") instanceof String uri)
                        || !uri.matches("lb://[a-z0-9][a-z0-9-]*")) {
                    continue;
                }
                String service = uri.substring(5);
                String prefix = null;
                for (Object predicate : list(route.get("predicates"))) {
                    if (predicate instanceof String p && p.matches("Path=/[a-z0-9][a-z0-9-]*/\\*\\*")) {
                        prefix = p.substring(5, p.length() - 3);
                    } else if (predicate instanceof Map<?, ?> p
                            && "Path".equals(p.get("name"))
                            && p.get("args") instanceof Map<?, ?> args) {
                        Object pattern = args.get("_genkey_0");
                        if (pattern instanceof String path && path.matches("/[a-z0-9][a-z0-9-]*/\\*\\*")) {
                            prefix = path.substring(0, path.length() - 3);
                        }
                    }
                }
                List<String> missing = new ArrayList<>();
                if (prefix == null) {
                    missing.add("ROUTE_PREFIX_MISSING");
                } else if (!Set.of("tenant", "platform").contains(realms.getOrDefault(prefix, ""))) {
                    missing.add("AUTH_PREFIX_MISSING");
                }
                var evidence = new ConfigurationEvidence(
                        missing.isEmpty() ? "CONFIGURED" : "INCOMPLETE",
                        "NACOS_CLIENT",
                        checkedAt,
                        prefix,
                        List.copyOf(missing));
                declarations
                        .computeIfAbsent(service, ignored -> new ArrayList<>())
                        .add(new Route(evidence, rootOpenApiRoute(route)));
            }
            Map<String, ConfigurationEvidence> entries = new TreeMap<>();
            declarations.forEach((service, routes) -> entries.put(service, summarize(routes, checkedAt)));
            return new Snapshot(entries, null, checkedAt);
        } catch (Exception error) {
            return unknown(checkedAt, "NACOS_READ_FAILED");
        }
    }

    private static ConfigurationEvidence summarize(List<Route> routes, Instant checkedAt) {
        if (routes.size() == 1) {
            return routes.get(0).evidence();
        }
        Set<String> prefixes = new HashSet<>();
        List<String> missing = new ArrayList<>();
        boolean duplicatePrefix = false;
        for (Route route : routes) {
            var evidence = route.evidence();
            missing.addAll(evidence.missing());
            if (evidence.prefix() != null && !prefixes.add(evidence.prefix())) {
                duplicatePrefix = true;
            }
        }
        String prefix = routes.stream()
                .filter(Route::rootOpenApi)
                .map(route -> route.evidence().prefix())
                .filter(Objects::nonNull)
                .sorted()
                .findFirst()
                .orElse(null);
        if (duplicatePrefix || prefix == null) {
            prefix = null;
            missing.add("MULTIPLE_ROUTES");
        }
        return new ConfigurationEvidence(
                missing.isEmpty() ? "CONFIGURED" : "INCOMPLETE",
                "NACOS_CLIENT",
                checkedAt,
                prefix,
                missing.stream().distinct().toList());
    }

    private static boolean rootOpenApiRoute(Map<?, ?> route) {
        // 只剥离服务前缀的路由能到达根 OpenAPI；PrefixPath 等改写后的入口不能直接拼接探测路径。
        var filters = list(route.get("filters"));
        if (filters.size() != 1) {
            return false;
        }
        Object filter = filters.get(0);
        if (filter instanceof String value) {
            return "StripPrefix=1".equals(value);
        }
        return filter instanceof Map<?, ?> value
                && "StripPrefix".equals(value.get("name"))
                && value.get("args") instanceof Map<?, ?> args
                && "1".equals(String.valueOf(args.get("parts")));
    }

    private record Route(ConfigurationEvidence evidence, boolean rootOpenApi) {}

    private static Object at(Map<String, Object> root, String... path) {
        Object value = root;
        for (String key : path) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            value = map.get(key);
        }
        return value;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static Snapshot unknown(Instant at, String reason) {
        return new Snapshot(Map.of(), reason, at);
    }

    public record Snapshot(Map<String, ConfigurationEvidence> entries, String problem, Instant checkedAt) {
        public ConfigurationEvidence forService(String service) {
            return entries.getOrDefault(
                    service,
                    new ConfigurationEvidence(
                            problem == null ? "INCOMPLETE" : "UNKNOWN",
                            "NACOS_CLIENT",
                            checkedAt,
                            null,
                            List.of(problem == null ? "ROUTE_MISSING" : problem)));
        }
    }
}
