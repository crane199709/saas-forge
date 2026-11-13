package io.saasforge.demo;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 导出测试的 IAM HTTP 边界替身，保留真实策略客户端与工作簿、存储和任务管线。 */
final class TestExportMasking {
    private static final Map<UUID, String> POLICIES = new ConcurrentHashMap<>();
    private static final HttpServer IAM;

    static {
        try {
            IAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IAM.createContext("/internal/field-masking/", exchange -> {
                boolean authorized = "Bearer export-masking-token"
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                UUID tenant = UUID.fromString(
                        exchange.getRequestURI().getPath().substring("/internal/field-masking/".length()));
                byte[] body = ("{\"code\":\"ok\",\"httpStatus\":200,\"data\":" + POLICIES.getOrDefault(tenant, "{}")
                                + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            IAM.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("forge.field-masking.enabled", () -> true);
        registry.add(
                "forge.field-masking.iam-uri",
                () -> "http://127.0.0.1:" + IAM.getAddress().getPort());
        registry.add("forge.field-masking.token", () -> "export-masking-token");
    }

    static void policies(UUID tenant, String json) {
        POLICIES.put(tenant, json);
    }

    private TestExportMasking() {}
}
