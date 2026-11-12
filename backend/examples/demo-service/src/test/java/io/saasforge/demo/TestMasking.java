package io.saasforge.demo;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import io.saasforge.sdk.security.MaskingRule;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.json.JsonMapper;

/** IAM 为系统边界替身；字段清单来自真实 Manifest 收集，不在测试中重列字段路径。 */
final class TestMasking {
    private static final String TOKEN = "test-masking-service-token";
    private static final Map<UUID, String> FAILURES = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> CHANGING = new ConcurrentHashMap<>();
    private static final HttpServer IAM;

    static {
        try {
            IAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IAM.createContext("/internal/field-masking/", exchange -> {
                boolean authorized =
                        ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                UUID tenant = UUID.fromString(
                        exchange.getRequestURI().getPath().substring("/internal/field-masking/".length()));
                var manifest = ResourceManifestCollector.collect(
                        "demo-service", "8", "io.saasforge.demo", TestMasking.class.getClassLoader());
                Map<String, EffectiveFieldPolicy> policies = new LinkedHashMap<>();
                for (var field : manifest.sensitiveFields()) {
                    policies.put(
                            field.path(),
                            new EffectiveFieldPolicy(
                                    field.path(),
                                    field.grade(),
                                    new MaskingRule(field.defaultRule(), null, null),
                                    !"L4".equals(field.grade())));
                }
                String failure = FAILURES.get(tenant);
                if ("missingField".equals(failure)) policies.remove("profile.idCard");
                if ("missingRule".equals(failure))
                    policies.put("profile.idCard", new EffectiveFieldPolicy("profile.idCard", "L3", null, true));
                if ("engine".equals(failure))
                    policies.put(
                            "profile.idCard",
                            new EffectiveFieldPolicy(
                                    "profile.idCard", "L3", new MaskingRule("keepFirst", 100, null), true));
                var changes = CHANGING.get(tenant);
                if (changes != null && changes.getAndIncrement() > 0) {
                    policies.replaceAll((path, policy) -> new EffectiveFieldPolicy(
                            path, policy.grade(), new MaskingRule("replace", null, "[changed]"), true));
                }
                byte[] body = JsonMapper.builder().build().writeValueAsBytes(ApiResponse.ok(policies));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(
                        !authorized ? 401 : "unavailable".equals(failure) ? 503 : 200, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            IAM.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private TestMasking() {}

    static void fail(UUID tenant, String mode) {
        FAILURES.put(tenant, mode);
    }

    static void changeAfterFirstLookup(UUID tenant) {
        CHANGING.put(tenant, new AtomicInteger());
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("forge.field-masking.enabled", () -> true);
        registry.add(
                "forge.field-masking.iam-uri",
                () -> "http://127.0.0.1:" + IAM.getAddress().getPort());
        registry.add("forge.field-masking.token", () -> TOKEN);
    }
}
