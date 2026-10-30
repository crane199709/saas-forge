package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.saasforge.platform.tenant.mapper.CoolingOffMapper;
import io.saasforge.platform.tenant.mapper.OutboxAuditMapper;
import io.saasforge.platform.tenant.service.TenantLifecycleService;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.provisioning.TenantExpiryApproached;
import io.saasforge.sdk.provisioning.TenantStatusChanged;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** Operator 按合法迁移表推进 Tenant Lifecycle，每次变更广播；删除进入冷静期，期内可撤回，期满广播 TenantDeleted（工单 15）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plan.sql")
class TenantLifecycleTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        PlatformTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    TenantLifecycleService lifecycle;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    CoolingOffMapper coolingOff;

    @Test
    void illegalTransitionsAreRejectedAndLegalOnesAreBroadcastAndRecorded() {
        UUID tenantId = create(Map.of("name", "生命周期", "adminContact", "owner@example.com"));

        for (String target : List.of("archived", "pending", "active", "unknown")) {
            Response rejected = transition(tenantId, Map.of("status", target));
            assertThat(rejected.status()).as(target).isEqualTo(409);
            assertThat(rejected.code()).isEqualTo("tenant.illegalTransition");
        }
        // 到期时间只能随迁移到试用或正常设置
        assertThat(transition(tenantId, Map.of("status", "suspended", "expiresAt", future(10)))
                        .code())
                .isEqualTo("tenant.invalidExpiry");
        assertThat(events(tenantId, TenantStatusChanged.class)).isEmpty();

        Response suspended = transition(tenantId, Map.of("status", "suspended", "reason", " 欠费 "));

        assertThat(suspended.status()).isEqualTo(200);
        assertThat(tenant(suspended)).containsEntry("status", "suspended");
        assertThat(suspended.data().get("allowedTransitions"))
                .isEqualTo(List.of("trial", "active", "archived", "deleted"));
        assertThat(events(tenantId, TenantStatusChanged.class))
                .containsExactly(new TenantStatusChanged(tenantId, "生命周期", "active", "suspended", 1));
        List<Map<String, Object>> history = history(suspended);
        assertThat(history).hasSize(2);
        assertThat(history.get(0))
                .containsEntry("fromStatus", "active")
                .containsEntry("toStatus", "suspended")
                .containsEntry("reason", "欠费")
                .containsEntry("changedBy", PlatformTokens.OPERATOR_ID);
        assertThat(history.get(1)).containsEntry("fromStatus", null).containsEntry("toStatus", "active");

        assertThat(transition(tenantId, Map.of("status", "active")).status()).isEqualTo(200);
        assertThat(events(tenantId, TenantStatusChanged.class))
                .last()
                .isEqualTo(new TenantStatusChanged(tenantId, "生命周期", "suspended", "active", 2));
    }

    @Test
    void deletionCanBeWithdrawnDuringCoolingOffAndIsBroadcastOnceWhenItEnds() {
        UUID tenantId = create(Map.of("name", "待删除", "adminContact", "13900000000"));

        Response deleted = transition(tenantId, Map.of("status", "deleted"));

        assertThat(tenant(deleted)).containsEntry("status", "deleted");
        assertThat(OffsetDateTime.parse((String) tenant(deleted).get("purgeAt")))
                .isCloseTo(OffsetDateTime.now().plusDays(30), within(1, ChronoUnit.MINUTES));
        // 冷静期内只能撤回到删除前的状态
        assertThat(deleted.data().get("allowedTransitions")).isEqualTo(List.of("active"));
        assertThat(transition(tenantId, Map.of("status", "suspended")).code()).isEqualTo("tenant.illegalTransition");

        Response withdrawn = transition(tenantId, Map.of("status", "active", "reason", "误删"));
        assertThat(tenant(withdrawn)).containsEntry("status", "active").containsEntry("purgeAt", null);

        transition(tenantId, Map.of("status", "deleted"));
        lifecycle.purgeDue();
        assertThat(events(tenantId, TenantDeleted.class)).isEmpty();

        coolingOff.endCoolingOff(tenantId);
        // 冷静期结束后不能再撤回
        assertThat(transition(tenantId, Map.of("status", "active")).code()).isEqualTo("tenant.illegalTransition");
        lifecycle.purgeDue();
        lifecycle.purgeDue();

        assertThat(events(tenantId, TenantDeleted.class)).containsExactly(new TenantDeleted(tenantId));
        Response detail = call("GET", "/tenants/" + tenantId, null);
        assertThat(tenant(detail).get("purgedAt")).isNotNull();
        assertThat(detail.data().get("allowedTransitions")).isEqualTo(List.of());
        assertThat(events(tenantId, TenantStatusChanged.class))
                .extracting(TenantStatusChanged::status)
                .containsExactly("deleted", "active", "deleted");
    }

    @Test
    void expiryReminderIsPublishedOncePerExpiry() {
        UUID tenantId = create(
                Map.of("name", "试用到期", "adminContact", "trial@example.com", "status", "trial", "expiresAt", future(3)));
        UUID later = create(Map.of(
                "name", "远未到期", "adminContact", "later@example.com", "status", "trial", "expiresAt", future(30)));

        lifecycle.remindDue();
        lifecycle.remindDue();

        List<TenantExpiryApproached> reminders = events(tenantId, TenantExpiryApproached.class);
        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).status()).isEqualTo("trial");
        assertThat(reminders.get(0).name()).isEqualTo("试用到期");
        assertThat(events(later, TenantExpiryApproached.class)).isEmpty();

        // 转正并设置新的到期时间后，新的到期时间再提醒一次
        assertThat(transition(tenantId, Map.of("status", "active", "expiresAt", future(2)))
                        .status())
                .isEqualTo(200);
        lifecycle.remindDue();
        assertThat(events(tenantId, TenantExpiryApproached.class))
                .extracting(TenantExpiryApproached::status)
                .containsExactly("trial", "active");
    }

    private static String future(int days) {
        return OffsetDateTime.now().plusDays(days).toString();
    }

    private UUID create(Map<String, Object> request) {
        var body = new HashMap<>(request);
        body.put("planId", PlatformTokens.DEFAULT_PLAN_ID);
        Response created = call("POST", "/tenants", body);
        assertThat(created.status()).isEqualTo(201);
        return UUID.fromString((String) tenant(created).get("id"));
    }

    private Response transition(UUID tenantId, Map<String, Object> request) {
        return call("POST", "/tenants/" + tenantId + "/transitions", new HashMap<>(request));
    }

    private <T> List<T> events(UUID tenantId, Class<T> type) {
        return outbox.payloads(tenantId, type.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, type))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tenant(Response response) {
        return (Map<String, Object>) response.data().get("tenant");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> history(Response response) {
        return (List<Map<String, Object>>) response.data().get("statusChanges");
    }

    private Response call(String method, String path, Object body) {
        try {
            var request = RestClient.create("http://localhost:" + port)
                    .method(HttpMethod.valueOf(method))
                    .uri(path)
                    .header("Authorization", "Bearer " + PlatformTokens.operator());
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange(
                    (req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(ENVELOPE)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Response(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }

        String code() {
            return (String) body.get("code");
        }
    }
}
