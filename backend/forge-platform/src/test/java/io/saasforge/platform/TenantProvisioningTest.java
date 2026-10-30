package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.tenant.mapper.OutboxAuditMapper;
import io.saasforge.platform.tenant.service.TenantService;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializationReported;
import io.saasforge.sdk.provisioning.TenantInitializationRequested;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** Operator 创建租户与 TenantCreated 同一事务提交，平台按各服务回报展示初始化状态，失败项可重试（工单 14）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plan.sql")
class TenantProvisioningTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        PlatformTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    TenantService tenants;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void createdTenantIsUsableAtOnceAndPublishesTenantCreated() throws Exception {
        Response created = call(
                "POST",
                "/tenants",
                Map.of(
                        "name",
                        " 示例租户 ",
                        "adminContact",
                        "Owner@Example.com",
                        "planId",
                        PlatformTokens.DEFAULT_PLAN_ID));

        assertThat(created.status()).isEqualTo(201);
        Map<?, ?> tenant = (Map<?, ?>) created.data().get("tenant");
        UUID tenantId = UUID.fromString((String) tenant.get("id"));
        assertThat(tenant.get("name")).isEqualTo("示例租户");
        assertThat(tenant.get("status")).isEqualTo("active");
        assertThat(statuses(created.data()))
                .containsExactly(Map.entry("demo-service", "pending"), Map.entry("forge-iam", "pending"));
        assertThat(tenants.get(tenantId).getCreatedBy()).hasToString(PlatformTokens.OPERATOR_ID);
        // 事件带新租户的上下文发布，订阅方据此恢复租户
        assertThat(payloads(tenantId, TenantCreated.class, TenantCreated.class))
                .containsExactly(new TenantCreated(tenantId, "示例租户", "Owner@Example.com", "active"));
        // Operator 的业务操作以 @AuditLog 记为 Platform Realm 事件：没有租户上下文，操作人取自 Platform Realm Token
        assertThat(outbox.globalPayloads(AuditEvent.class.getSimpleName()).stream()
                        .map(payload -> JSON.readValue(payload, AuditEvent.class))
                        .filter(event -> event.action().equals("tenant.create")
                                && tenantId.toString().equals(event.resourceId())))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
                    assertThat(event.tenantId()).isNull();
                    assertThat(event.actorId()).hasToString(PlatformTokens.OPERATOR_ID);
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
                    assertThat(event.service()).isEqualTo("forge-platform");
                });

        Response list = call("GET", "/tenants?page=1&size=100", null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("meta")).isNotNull();
        assertThat((List<?>) list.body().get("data"))
                .anySatisfy(item -> assertThat(((Map<?, ?>) item).get("id")).isEqualTo(tenantId.toString()));
    }

    @Test
    void rolledBackCreationLeavesNoTenantAndNoEvent() {
        var tenantId = new AtomicReference<UUID>();
        transaction.executeWithoutResult(status -> {
            tenantId.set(tenants.create(
                            "回滚的租户",
                            "rollback@example.com",
                            null,
                            "active",
                            null,
                            UUID.fromString(PlatformTokens.DEFAULT_PLAN_ID))
                    .getId());
            status.setRollbackOnly();
        });

        assertThat(call("GET", "/tenants/" + tenantId.get(), null).status()).isEqualTo(404);
        assertThat(outbox.payloads(tenantId.get(), TenantCreated.class.getSimpleName()))
                .isEmpty();
    }

    @Test
    void reportsUpdateStatusAndOnlyFailedInitializationCanBeRetried() throws Exception {
        UUID tenantId = UUID.fromString((String) ((Map<?, ?>) call(
                                "POST",
                                "/tenants",
                                Map.of(
                                        "name",
                                        "重试租户",
                                        "adminContact",
                                        "13800000000",
                                        "planId",
                                        PlatformTokens.DEFAULT_PLAN_ID))
                        .data()
                        .get("tenant"))
                .get("id"));

        deliver(tenantId, new TenantInitializationReported(tenantId, "forge-iam", true, null));
        deliver(tenantId, new TenantInitializationReported(tenantId, "demo-service", false, "boom"));
        // 未预建的服务回报时照常记录
        deliver(tenantId, new TenantInitializationReported(tenantId, "billing-service", true, null));

        Response detail = call("GET", "/tenants/" + tenantId, null);
        assertThat(statuses(detail.data()))
                .containsExactly(
                        Map.entry("billing-service", "succeeded"),
                        Map.entry("demo-service", "failed"),
                        Map.entry("forge-iam", "succeeded"));
        assertThat((List<?>) detail.data().get("initializations"))
                .anySatisfy(item -> assertThat(((Map<?, ?>) item).get("error")).isEqualTo("boom"));

        assertThat(call("POST", "/tenants/" + tenantId + "/initializations/forge-iam/retry", null)
                        .code())
                .isEqualTo("tenant.initializationNotFailed");
        assertThat(call("POST", "/tenants/" + tenantId + "/initializations/unknown/retry", null)
                        .status())
                .isEqualTo(404);

        Response retried = call("POST", "/tenants/" + tenantId + "/initializations/demo-service/retry", null);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.data()).containsEntry("status", "pending").containsEntry("error", null);
        assertThat(payloads(tenantId, TenantInitializationRequested.class, TenantInitializationRequested.class))
                .containsExactly(new TenantInitializationRequested(
                        "demo-service", new TenantCreated(tenantId, "重试租户", "13800000000", "active")));
        // 重试进行中不能再次重试；成功回报后状态更新
        assertThat(call("POST", "/tenants/" + tenantId + "/initializations/demo-service/retry", null)
                        .status())
                .isEqualTo(409);
        deliver(tenantId, new TenantInitializationReported(tenantId, "demo-service", true, null));
        assertThat(statuses(call("GET", "/tenants/" + tenantId, null).data()))
                .contains(Map.entry("demo-service", "succeeded"));
    }

    /** 与 SDK 消费端一致：先恢复发布方的租户，再在事务中调用处理器。 */
    @SuppressWarnings("unchecked")
    private <T> void deliver(UUID tenantId, T event) {
        var handler = (EventHandler<T>) handlers.stream()
                .filter(candidate -> candidate.eventType() == event.getClass())
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenantId, () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    private <T> List<T> payloads(UUID tenantId, Class<?> tag, Class<T> type) {
        return outbox.payloads(tenantId, tag.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, type))
                .toList();
    }

    private static List<Map.Entry<Object, Object>> statuses(Map<String, Object> detail) {
        return ((List<?>) detail.get("initializations"))
                .stream()
                        .map(item -> Map.entry(((Map<?, ?>) item).get("service"), ((Map<?, ?>) item).get("status")))
                        .toList();
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
