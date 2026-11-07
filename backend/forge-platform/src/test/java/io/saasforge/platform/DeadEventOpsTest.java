package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.ops.mapper.DeadEventFixtureMapper;
import io.saasforge.platform.tenant.mapper.OutboxAuditMapper;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class DeadEventOpsTest extends PlatformIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper audit;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void replayAndAbandonRecordTheOperatorReasonServiceAndEvent() throws Exception {
        for (String action : List.of("replay", "abandon")) {
            UUID id = UUID.randomUUID();
            Reply reply = command("demo-service", id, action, "已确认处置原因", PlatformTokens.operator());
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.body()).contains(id.toString()).doesNotContain("payload");
            var events = audit.globalPayloads("AuditEvent").stream()
                    .map(json::readTree)
                    .filter(event ->
                            id.toString().equals(event.path("resourceId").asString()))
                    .toList();
            assertThat(events).hasSize(1);
            var event = events.get(0);
            assertThat(event.path("action").asString())
                    .isEqualTo(action.equals("replay") ? "ops.deadEventReplay" : "ops.deadEventAbandon");
            assertThat(event.path("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(event.path("actorId").asString()).isEqualTo(PlatformTokens.OPERATOR_ID);
            assertThat(event.path("detail").path("service").asString()).isEqualTo("demo-service");
            assertThat(event.path("detail").path("reason").asString()).isEqualTo("已确认处置原因");
        }
    }

    @Test
    void readsMetadataWithPaginationWithoutExposingThePayload() throws Exception {
        var page =
                read("/ops/services/demo-service/dead-events?status=PENDING&page=1&size=2", PlatformTokens.operator());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("original-event", "totalElements", "PENDING")
                .doesNotContain("payload", "originalTenantId");
        UUID id = UUID.randomUUID();
        assertThat(read("/ops/services/demo-service/dead-events/" + id, PlatformTokens.operator())
                        .body())
                .contains(id.toString());
    }

    @Test
    void rejectedCommandsAreAuditedAsFailures() throws Exception {
        UUID id = UUID.randomUUID();
        Reply reply = command("demo-service", id, "replay", "reject", PlatformTokens.operator());
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.body()).contains("ops.deadEventAlreadyHandled");
        var events = audit.globalPayloads("AuditEvent").stream()
                .map(json::readTree)
                .filter(event -> id.toString().equals(event.path("resourceId").asString()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("outcome").asString()).isEqualTo("FAILURE");
        assertThat(events.get(0).path("errorCode").asString()).isEqualTo("ops.deadEventAlreadyHandled");
        assertThat(command("demo-service", UUID.randomUUID(), "abandon", "  ", PlatformTokens.operator())
                        .status())
                .isEqualTo(400);
    }

    @Test
    void readAndManagePermissionsAndTheOperatorRealmAreEnforced() throws Exception {
        String readOnly = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:read"), null, List.of());
        assertThat(command("demo-service", UUID.randomUUID(), "replay", "reason", readOnly)
                        .status())
                .isEqualTo(403);
        assertThat(command("demo-service", UUID.randomUUID(), "abandon", "reason", readOnly)
                        .status())
                .isEqualTo(403);
        String manageOnly = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:manage"), null, List.of());
        assertThat(read("/ops/services/demo-service/dead-events", manageOnly).status())
                .isEqualTo(403);
        String tenantToken = PlatformTokens.token(
                "http://tenant-realm.test", "tenant-app", UUID.randomUUID().toString());
        assertThat(command("demo-service", UUID.randomUUID(), "replay", "reason", tenantToken)
                        .status())
                .isEqualTo(401);
    }

    @Autowired
    DeadEventFixtureMapper deadEvents;

    @Autowired
    ApplicationContext application;

    @Autowired
    PlatformTransactionManager manager;

    @Test
    void tenantDeletionCleansOnlyItsOwnDeadEventsAndIsIdempotent() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID ownEvent = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        deadEvents.capture(ownEvent, target, target.toString(), "Removed", "{}".getBytes());
        deadEvents.capture(otherEvent, other, other.toString(), "Removed", "{}".getBytes());
        EventHandler<TenantDeleted> handler = (EventHandler<TenantDeleted>) application.getBean("tenantDeletedHandler");
        for (int attempt = 0; attempt < 2; attempt++) {
            CurrentTenant.run(
                    target,
                    () -> new TransactionTemplate(manager)
                            .executeWithoutResult(status -> handler.handle(new TenantDeleted(target))));
        }
        String token = "test-forge-platform-ops-token";
        assertThat(read("/internal/ops/dead-events/" + ownEvent, token).status())
                .isEqualTo(404);
        assertThat(read("/internal/ops/dead-events/" + otherEvent, token).status())
                .isEqualTo(200);
    }

    @Test
    void replayOfTenantDeletionSucceedsEvenWhenCleanupRemovesItsOwnDeadEvent() {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        deadEvents.capture(
                id, tenant, tenant.toString(), "TenantDeleted", json.writeValueAsBytes(new TenantDeleted(tenant)));
        Reply reply = RestClient.create("http://localhost:" + port)
                .post()
                .uri("/internal/ops/dead-events/" + id + "/replay")
                .header("Authorization", "Bearer test-forge-platform-ops-token")
                .body(Map.of("reason", "完成租户删除"))
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("REPLAYED").doesNotContain("payload");
        assertThat(read("/internal/ops/dead-events/" + id, "test-forge-platform-ops-token")
                        .status())
                .isEqualTo(404);
    }

    Reply read(String path, String token) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    Reply command(String service, UUID id, String action, String reason, String token) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/ops/services/" + service + "/dead-events/" + id + "/" + action)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("reason", reason))
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    record Reply(int status, String body) {}
}
