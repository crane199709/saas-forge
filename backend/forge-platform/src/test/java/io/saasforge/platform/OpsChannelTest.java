package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.ops.service.OpsClient;
import io.saasforge.sdk.core.api.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

class OpsChannelTest extends PlatformIntegrationTest {
    @Autowired
    OpsClient ops;

    @Test
    void serviceBusinessConflictRetainsItsStatusAndErrorCode() {
        var reply = ops.post(
                "demo-service",
                "/command-rejected",
                Map.of("reason", "重放已修复的事件"),
                new ParameterizedTypeReference<ApiResponse<Boolean>>() {});
        assertThat(reply.httpStatus()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("deadEvent.alreadyHandled");
    }

    @Test
    void serviceCallsKeepPaginationQueryParametersAndCommandReasons() {
        var page =
                ops.get("demo-service", "/page?size=2", new ParameterizedTypeReference<ApiResponse<List<String>>>() {});
        assertThat(page.httpStatus()).isEqualTo(200);
        assertThat(page.data()).containsExactly("event-a", "event-b");
        assertThat(page.meta().page().totalElements()).isEqualTo(3);
        var command = ops.post(
                "demo-service",
                "/command",
                Map.of("reason", "重放已修复的事件"),
                new ParameterizedTypeReference<ApiResponse<Boolean>>() {});
        assertThat(command.httpStatus()).isEqualTo(200);
        assertThat(command.data()).isTrue();
    }

    @LocalServerPort
    int port;

    @Test
    void operatorWithoutReadPermissionAndTenantTokenAreRejected() throws Exception {
        String restricted = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:manage"), null, List.of());
        int forbidden = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/ops/services")
                .header("Authorization", "Bearer " + restricted)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(forbidden).isEqualTo(403);
        String tenant = PlatformTokens.token(
                "http://tenant-realm.test", "tenant-app", UUID.randomUUID().toString());
        int rejected = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/ops/services")
                .header("Authorization", "Bearer " + tenant)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(rejected).isEqualTo(401);
    }

    @Test
    void operatorSeesReportedServicesAndUnavailableServicesDoNotHideHealthyOnes() throws Exception {
        var response = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/ops/services")
                .header("Authorization", "Bearer " + PlatformTokens.operator())
                .exchange((request, result) -> {
                    assertThat(result.getStatusCode().value()).isEqualTo(200);
                    return result.bodyTo(Map.class);
                });
        assertThat((List<Map<String, Object>>) response.get("data"))
                .containsExactly(
                        Map.of("service", "demo-service", "available", true),
                        Map.of("service", "offline-service", "available", false),
                        Map.of("service", "unsupported-service", "available", false),
                        Map.of("service", "unreachable-service", "available", false),
                        Map.of("service", "unconfigured-service", "available", false));
    }
}
