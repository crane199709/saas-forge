package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.tenant.mapper.OutboxAuditMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class TaskOpsTest extends PlatformIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper audit;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void triggersAndTenantRetriesUseTheAuthenticatedOperatorAndRecordTheirRunId() throws Exception {
        for (boolean retry : List.of(false, true)) {
            String name = "task-" + UUID.randomUUID();
            UUID target = UUID.randomUUID();
            Map<String, Object> body = retry
                    ? Map.of("targetTenantId", target, "operatorId", UUID.randomUUID())
                    : Map.of("operatorId", UUID.randomUUID());
            Reply reply = trigger(name, body, PlatformTokens.operator());
            assertThat(reply.status()).isEqualTo(200);
            String runId =
                    json.readTree(reply.body()).path("data").path("runId").asString();
            assertThat(runId).isNotBlank();
            var events = audit.globalPayloads("AuditEvent").stream()
                    .map(json::readTree)
                    .filter(event -> name.equals(event.path("resourceId").asString()))
                    .toList();
            assertThat(events).hasSize(1);
            var event = events.get(0);
            assertThat(event.path("action").asString()).isEqualTo(retry ? "ops.taskTenantRetry" : "ops.taskTrigger");
            assertThat(event.path("actorId").asString()).isEqualTo(PlatformTokens.OPERATOR_ID);
            assertThat(event.path("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(event.path("detail").path("service").asString()).isEqualTo("demo-service");
            assertThat(event.path("detail").path("runId").asString()).isEqualTo(runId);
            if (retry) {
                assertThat(event.path("detail").path("targetTenantId").asString())
                        .isEqualTo(target.toString());
            }
        }
    }

    @Test
    void readsDeclarationsRecentRunsAndProblemRunsIncludingFailedTenants() throws Exception {
        String token = PlatformTokens.operator();
        Reply tasks = read("/ops/services/demo-service/tasks", token);
        assertThat(tasks.status()).isEqualTo(200);
        assertThat(tasks.body()).contains("tenant", "PT1H", "latestRun");
        for (String view : List.of("recent", "problems")) {
            Reply runs = read("/ops/services/demo-service/tasks/tenant/runs?view=" + view, token);
            assertThat(runs.status()).isEqualTo(200);
            assertThat(runs.body()).contains("FAILED", "failedTenants", "IllegalStateException");
        }
        assertThat(read("/ops/services/demo-service/tasks/tenant/runs?view=unknown", token)
                        .status())
                .isEqualTo(400);
        assertThat(read("/ops/services/not-listed/tasks", token).status()).isEqualTo(404);
    }

    @Test
    void businessRejectionsKeepTheSdkErrorCodeAndAreAuditedAsFailures() throws Exception {
        for (String name : List.of("busy", "ineligible")) {
            UUID target = UUID.randomUUID();
            Reply reply = trigger(
                    name, name.equals("busy") ? Map.of() : Map.of("targetTenantId", target), PlatformTokens.operator());
            String expected = name.equals("busy") ? "task.alreadyRunning" : "task.tenantNotEligible";
            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.body()).contains(expected);
            var events = audit.globalPayloads("AuditEvent").stream()
                    .map(json::readTree)
                    .filter(event -> name.equals(event.path("resourceId").asString()))
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).path("outcome").asString()).isEqualTo("FAILURE");
            assertThat(events.get(0).path("errorCode").asString()).isEqualTo(expected);
        }
    }

    @Test
    void readAndManagePermissionsAndThePlatformRealmProtectTheEndpoints() throws Exception {
        String readOnly = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:read"), null, List.of());
        assertThat(read("/ops/services/demo-service/tasks", readOnly).status()).isEqualTo(200);
        assertThat(trigger("global", Map.of(), readOnly).status()).isEqualTo(403);
        assertThat(trigger("tenant", Map.of("targetTenantId", UUID.randomUUID()), readOnly)
                        .status())
                .isEqualTo(403);
        String manageOnly = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:manage"), null, List.of());
        assertThat(read("/ops/services/demo-service/tasks", manageOnly).status())
                .isEqualTo(403);
        assertThat(read("/ops/services/demo-service/tasks/tenant/runs?view=recent", manageOnly)
                        .status())
                .isEqualTo(403);
        String tenantToken = PlatformTokens.token(
                "http://tenant-realm.test", "tenant-app", UUID.randomUUID().toString());
        assertThat(trigger("global", Map.of(), tenantToken).status()).isEqualTo(401);
    }

    @Test
    void taskNamesAreEncodedOnceWhenCallingTheOwningService() throws Exception {
        String name = "任务 空格";
        assertThat(trigger(name, Map.of(), PlatformTokens.operator()).status()).isEqualTo(200);
        assertThat(read("/ops/services/demo-service/tasks/" + name + "/runs", PlatformTokens.operator())
                        .status())
                .isEqualTo(200);
    }

    Reply read(String path, String token) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    Reply trigger(String name, Object body, String token) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/ops/services/demo-service/tasks/" + name + "/trigger")
                .header("Authorization", "Bearer " + token)
                .body(body)
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    record Reply(int status, String body) {}
}
