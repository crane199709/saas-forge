package io.saasforge.platform;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 所属服务的 HTTP 边界替身；数据库与重放事务由 MQ 模块用真实 PostgreSQL 验证。 */
final class DeadEventOpsFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static void handle(HttpExchange exchange) throws IOException {
        if (!"Bearer demo-ops-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, Map.of("code", "auth.unauthenticated", "httpStatus", 401));
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String id = parts.length > 4 ? parts[4] : "00000000-0000-0000-0000-000000000001";
        boolean command = exchange.getRequestMethod().equals("POST");
        String status = command ? (parts[5].equals("abandon") ? "ABANDONED" : "REPLAYED") : "PENDING";
        String reason = command
                ? JSON.readTree(exchange.getRequestBody()).path("reason").asString()
                : "test";
        if (reason.equals("reject")) {
            send(exchange, 409, Map.of("code", "deadEvent.alreadyHandled", "httpStatus", 409));
            return;
        }
        var metadata = Map.of(
                "id",
                id,
                "eventId",
                "original-event",
                "status",
                status,
                "resolutionReason",
                reason,
                "deliveryAttempt",
                16);
        if (parts.length == 4) {
            boolean queryValid = "status=PENDING&page=1&size=2"
                    .equals(exchange.getRequestURI().getRawQuery());
            send(
                    exchange,
                    queryValid ? 200 : 400,
                    Map.of(
                            "code",
                            "ok",
                            "httpStatus",
                            200,
                            "data",
                            List.of(metadata),
                            "meta",
                            Map.of("page", Map.of("number", 1, "size", 2, "totalElements", 3, "totalPages", 2))));
        } else {
            send(exchange, 200, Map.of("code", "ok", "httpStatus", 200, "data", metadata));
        }
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
