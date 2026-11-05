package io.saasforge.demo;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 模拟 iam 的 {@code GET /internal/reference-data/{tenantId}}：demo-service 的「客户来源」字典与参数 {@code order.auto-close}。
 * 每个租户的转介绍（{@code referral}）缺省为停用，其余项可选；测试可改变某租户的状态，模拟租户在 iam 中的修改。
 */
final class TestReferenceData {
    static final String TOKEN = "test-reference-data-token";
    private static final Map<UUID, String> REFERRAL = new ConcurrentHashMap<>();
    private static final HttpServer IAM;

    static {
        try {
            IAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IAM.createContext("/internal/reference-data/", exchange -> {
                UUID tenant = UUID.fromString(
                        exchange.getRequestURI().getPath().substring("/internal/reference-data/".length()));
                boolean authorized =
                        ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = (authorized ? body(REFERRAL.getOrDefault(tenant, "disabled")) : "{}")
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
    }

    static String uri() {
        return "http://127.0.0.1:" + IAM.getAddress().getPort();
    }

    /** 租户在 iam 中启用或停用了转介绍。 */
    static void referral(UUID tenantId, String status) {
        REFERRAL.put(tenantId, status);
    }

    private static String body(String referralStatus) {
        return """
                {"code":"ok","httpStatus":200,"data":{"dictionaries":[
                  {"service":"demo-service","code":"customer-source","name":"客户来源","extensible":true,"items":[
                    {"code":"online","name":"线上","sort":1,"custom":false,"status":"enabled"},
                    {"code":"referral","name":"转介绍","sort":2,"custom":false,"status":"%s"},
                    {"code":"custom.fair","name":"展会","sort":3,"custom":true,"status":"enabled"}]}],
                 "parameters":[
                  {"service":"demo-service","code":"order.auto-close","type":"duration","value":"PT30M"}]}}
                """.formatted(referralStatus);
    }
}
