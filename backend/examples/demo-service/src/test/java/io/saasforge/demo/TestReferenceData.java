package io.saasforge.demo;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 模拟 iam 的租户目录与 {@code GET /internal/reference-data/{tenantId}}：demo-service 的「客户来源」字典与参数 {@code order.auto-close}。
 * 每个租户的转介绍（{@code referral}）缺省为停用，其余项可选；测试可改变某租户的状态，模拟租户在 iam 中的修改。
 */
final class TestReferenceData {
    static final String TOKEN = "test-reference-data-token";
    private static final Map<UUID, String> REFERRAL = new ConcurrentHashMap<>();
    private static final Map<UUID, Duration> AUTO_CLOSE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FAILURES = new ConcurrentHashMap<>();
    private static final Set<UUID> CONFLICTS = ConcurrentHashMap.newKeySet();

    static void conflict(UUID tenant) {
        CONFLICTS.add(tenant);
    }

    private static volatile List<UUID> tenants = List.of();
    private static final HttpServer IAM;

    static {
        try {
            IAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IAM.createContext("/internal/reference-data/", exchange -> {
                UUID tenant = UUID.fromString(
                        exchange.getRequestURI().getPath().substring("/internal/reference-data/".length()));
                boolean authorized =
                        ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                int status = authorized ? FAILURES.getOrDefault(tenant, 200) : 401;
                byte[] body = (status == 200
                                ? body(
                                                REFERRAL.getOrDefault(tenant, "disabled"),
                                                AUTO_CLOSE.getOrDefault(tenant, Duration.ofMinutes(30)))
                                        .replace("\"转介绍\"", CONFLICTS.contains(tenant) ? "\"线上\"" : "\"转介绍\"")
                                : "{}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            IAM.createContext("/internal/tenants", exchange -> {
                boolean authorized =
                        ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                String ids = tenants.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
                byte[] body = (authorized
                                ? "{\"code\":\"ok\",\"httpStatus\":200,\"data\":[" + ids
                                        + "],\"meta\":{\"page\":{\"number\":1,\"size\":100,\"totalElements\":"
                                        + tenants.size()
                                        + ",\"totalPages\":1}}}"
                                : "{}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            IAM.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private TestReferenceData() {}

    static void register(DynamicPropertyRegistry registry) {
        registry.add("forge.reference-data.iam-uri", TestReferenceData::uri);
        registry.add("forge.reference-data.token", () -> TOKEN);
        registry.add("forge.scheduling.iam-uri", TestReferenceData::uri);
        registry.add("forge.scheduling.token", () -> TOKEN);
    }

    static void tenants(List<UUID> ids) {
        tenants = List.copyOf(ids);
    }

    static void autoClose(UUID tenant, Duration timeout) {
        AUTO_CLOSE.put(tenant, timeout);
    }

    static void fail(UUID tenant) {
        FAILURES.put(tenant, 503);
    }

    static String uri() {
        return "http://127.0.0.1:" + IAM.getAddress().getPort();
    }

    /** 租户在 iam 中启用或停用了转介绍。 */
    static void referral(UUID tenantId, String status) {
        REFERRAL.put(tenantId, status);
    }

    private static String body(String referralStatus, Duration timeout) {
        return """
                {"code":"ok","httpStatus":200,"data":{"dictionaries":[
                  {"service":"demo-service","code":"customer-source","name":"客户来源","extensible":true,"items":[
                    {"code":"online","name":"线上","sort":1,"custom":false,"status":"enabled"},
                    {"code":"referral","name":"转介绍","sort":2,"custom":false,"status":"%s"},
                    {"code":"custom.fair","name":"展会","sort":3,"custom":true,"status":"enabled"}]}],
                 "parameters":[
                  {"service":"demo-service","code":"order.auto-close","type":"duration","value":"%s"}]}}
                """.formatted(referralStatus, timeout);
    }
}
