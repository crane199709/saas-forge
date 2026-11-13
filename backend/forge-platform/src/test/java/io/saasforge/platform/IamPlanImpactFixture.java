package io.saasforge.platform;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 模拟 iam 的 {@code POST /internal/tenants/{id}/plan-impact}：未预置结果的租户返回 503，表示 iam 不可用。 */
final class IamPlanImpactFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<UUID, String> RESPONSES = new ConcurrentHashMap<>();
    private static final Map<UUID, JsonNode> REQUESTS = new ConcurrentHashMap<>();

    private IamPlanImpactFixture() {}

    static void stub(UUID tenantId, long memberCount, long affectedMemberships) {
        RESPONSES.put(
                tenantId,
                "{\"code\":\"ok\",\"httpStatus\":200,\"data\":{\"memberCount\":" + memberCount
                        + ",\"affectedMemberships\":" + affectedMemberships + "}}");
    }

    /** iam 收到的该租户最后一次请求体；未被调用时为 null。 */
    static JsonNode received(UUID tenantId) {
        return REQUESTS.get(tenantId);
    }

    static void handle(HttpExchange exchange) throws IOException {
        boolean authorized = ("Bearer " + PlatformTokens.IAM_TOKEN)
                .equals(exchange.getRequestHeaders().getFirst("Authorization"));
        UUID tenantId = UUID.fromString(exchange.getRequestURI().getPath().split("/")[3]);
        REQUESTS.put(tenantId, JSON.readTree(exchange.getRequestBody()));
        String response = RESPONSES.get(tenantId);
        int status = !authorized ? 401 : response == null ? 503 : 200;
        byte[] body = status == 200 ? response.getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
        if (status == 200) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
