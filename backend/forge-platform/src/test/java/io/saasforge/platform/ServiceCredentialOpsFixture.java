package io.saasforge.platform;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** IAM 是系统外部 HTTP 边界；真实凭证生命周期由 IAM 的 HTTP 测试验证。 */
final class ServiceCredentialOpsFixture {
    static volatile boolean available = true;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static void handle(HttpExchange exchange) throws IOException {
        if (!"Bearer test-iam-ops-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, Map.of("code", "auth.unauthenticated", "httpStatus", 401));
            return;
        }
        if (!available) {
            send(exchange, 503, Map.of("code", "service-credential.unavailable", "httpStatus", 503));
            return;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            send(exchange, 200, Map.of("code", "ok", "httpStatus", 200, "data", List.of()));
            return;
        }
        var request = JSON.readTree(exchange.getRequestBody());
        if (!PlatformTokens.OPERATOR_ID.equals(request.path("actor").asString())) {
            send(exchange, 400, Map.of("code", "service-credential.actorRequired", "httpStatus", 400));
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/disable")) {
            send(exchange, 200, Map.of("code", "ok", "httpStatus", 200));
            return;
        }
        send(
                exchange,
                201,
                Map.of(
                        "code",
                        "ok",
                        "httpStatus",
                        201,
                        "data",
                        Map.of(
                                "credential",
                                Map.of(
                                        "id",
                                        "019a0110-0000-7000-8000-000000000001",
                                        "service",
                                        "new-service",
                                        "status",
                                        "active",
                                        "fingerprint",
                                        "12345678"),
                                "token",
                                "test-one-time-secret",
                                "manifestReported",
                                false)));
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
