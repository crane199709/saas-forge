package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * 订单接口上的 {@code @AuditLog}：成功与失败各产生一条 Audit Event，写入 Outbox 等待投递（工单 23）。
 *
 * <p>测试不连接 MQ，事件停留在 Outbox 中，直接读出消息体校验；写入审计表与按租户查询见 iam 的 AuditTest。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000e1");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void successfulAndFailedOperationsAreAudited() throws Exception {
        TestGrants.publish(
                redisTemplate, TENANT, TestTokens.MEMBERSHIP_ID, List.of("order:create", "order:read", "order:update"));

        Map<String, Object> created = exchange(HttpMethod.POST, "/orders", Map.of("title", "审计订单", "amount", 9));
        String orderId = (String) ((Map<?, ?>) created.get("data")).get("id");
        UUID missing = UUID.randomUUID();
        assertThat(exchange(HttpMethod.PUT, "/orders/" + missing, Map.of("title", "x", "amount", 1))
                        .get("code"))
                .isEqualTo("order.notFound");
        // 权限不足在方法安全中被拒绝，审计包在其外，同样记为失败
        assertThat(exchange(HttpMethod.DELETE, "/orders/" + orderId, null).get("code"))
                .isEqualTo("auth.forbidden");
        // 读取没有声明 @AuditLog，不产生事件
        exchange(HttpMethod.GET, "/orders/" + orderId, null);

        List<AuditEvent> events = outbox.payloads(TENANT, AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .toList();
        assertThat(events)
                .extracting(AuditEvent::action)
                .containsExactly("order.create", "order.update", "order.delete");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
            assertThat(event.category()).isEqualTo(AuditCategory.OPERATION);
            assertThat(event.tenantId()).isEqualTo(TENANT);
            assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
            assertThat(event.resourceType()).isEqualTo("order");
            assertThat(event.service()).isEqualTo("demo-service");
            assertThat(event.occurredAt()).isNotNull();
        });

        AuditEvent create = events.get(0);
        assertThat(create.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(create.errorCode()).isNull();
        assertThat(create.resourceId()).isEqualTo(orderId);
        assertThat(create.traceId()).hasSize(32);

        AuditEvent update = events.get(1);
        assertThat(update.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(update.errorCode()).isEqualTo("order.notFound");
        assertThat(update.resourceId()).isEqualTo(missing.toString());

        AuditEvent delete = events.get(2);
        assertThat(delete.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(delete.errorCode()).isEqualTo("auth.forbidden");
        assertThat(delete.resourceId()).isEqualTo(orderId);
    }

    private Map<String, Object> exchange(HttpMethod method, String uri, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(TENANT));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange(
                (req, response) -> response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {}));
    }
}
