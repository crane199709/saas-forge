package io.saasforge.platform;

import com.sun.net.httpserver.HttpExchange;
import io.saasforge.sdk.scheduling.TaskDefinitionResponse;
import io.saasforge.sdk.scheduling.TaskRun;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** 所属服务的 HTTP 边界替身；SDK 侧执行、锁和记录由调度模块使用真实 Redis 验证。 */
final class TaskOpsFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static void handle(HttpExchange exchange) throws IOException {
        if (!"Bearer demo-ops-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, Map.of("code", "auth.unauthenticated", "httpStatus", 401));
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String name = parts.length > 4 ? parts[4] : "global";
        if (name.contains("%")) {
            send(exchange, 404, Map.of("code", "task.notFound", "httpStatus", 404));
            return;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            var run = new TaskRun(
                    UUID.fromString("019a0110-0000-7000-8000-000000000001"),
                    "tenant",
                    TaskRun.Type.TENANT,
                    TaskRun.Trigger.SCHEDULED,
                    null,
                    null,
                    TaskRun.Status.FAILED,
                    Instant.parse("2026-11-07T01:00:00Z"),
                    Instant.parse("2026-11-07T01:01:00Z"),
                    0,
                    1,
                    List.of(new TaskRun.FailedTenant(
                            UUID.fromString("019a0110-0000-7000-8000-000000000002"), "IllegalStateException")),
                    "IllegalStateException");
            Object data = parts.length == 4
                    ? List.of(new TaskDefinitionResponse("tenant", TaskRun.Type.TENANT, null, "PT1H", null, run))
                    : List.of(run);
            send(exchange, 200, Map.of("code", "ok", "httpStatus", 200, "data", data));
            return;
        }
        var request = JSON.readTree(exchange.getRequestBody());
        if (!PlatformTokens.OPERATOR_ID.equals(request.path("operatorId").asString())) {
            send(exchange, 400, Map.of("code", "request.invalid", "httpStatus", 400));
            return;
        }
        if (name.equals("busy") || name.equals("ineligible")) {
            send(
                    exchange,
                    409,
                    Map.of(
                            "code",
                            name.equals("busy") ? "task.alreadyRunning" : "task.tenantNotEligible",
                            "httpStatus",
                            409));
            return;
        }
        send(exchange, 200, Map.of("code", "ok", "httpStatus", 200, "data", Map.of("runId", UUID.randomUUID())));
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
