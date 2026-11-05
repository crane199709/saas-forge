package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.demo.category.entity.OrderCategory;
import io.saasforge.demo.category.service.OrderCategoryService;
import io.saasforge.demo.notification.service.OrderNotificationService;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderAuditMapper;
import io.saasforge.demo.order.model.OrderCreated;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.tenant.TenantWriteGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * demo-service 经 SDK 响应 Tenant Lifecycle（工单 15）：停用、过期等租户的写入被拒，服务可声明例外；
 * 冷静期结束后本服务该租户的数据被标记删除。
 *
 * <p>租户状态由测试写入 Redis，模拟 iam 按 platform 广播发布的状态；TenantDeleted 按消费端的方式直接调用 SDK 注册的处理器，不经 RocketMQ。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forge.tenant.write-guard.enabled=true")
class TenantLifecycleTest {
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

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
    OrderService orders;

    @Autowired
    OrderNotificationService notifications;

    @Autowired
    OrderCategoryService categories;

    @Autowired
    OrderAuditMapper orderAudit;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void writesAreRejectedWhileTenantIsSuspendedUnlessExempted() throws Exception {
        UUID tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        // 状态未发布时放行：网关已在键缺失时拒绝成员请求，这里不让开通等系统写入失败
        assertThat(call(tenant, HttpMethod.POST, Map.of("title", "未发布状态", "amount", 1))
                        .get("httpStatus"))
                .isEqualTo(201);

        for (String status : List.of("suspended", "expired", "archived", "deleted")) {
            setStatus(tenant, status);
            Map<String, Object> rejected = call(tenant, HttpMethod.POST, Map.of("title", "停用期间", "amount", 1));
            assertThat(rejected.get("httpStatus")).as(status).isEqualTo(403);
            assertThat(rejected.get("code")).isEqualTo("tenant.writeBlocked");
            assertThat(rejected.get("userTip")).isEqualTo("租户已停用或已过期，当前不能修改数据");
        }
        // 查询不受影响
        assertThat(call(tenant, HttpMethod.GET, null).get("httpStatus")).isEqualTo(200);
        // 消息消费、定时任务等无请求上下文的写入同样被拒，服务可声明例外
        assertThatThrownBy(() -> inTenant(tenant, () -> orders.create("后台写入", BigDecimal.ONE, null)))
                .hasRootCauseInstanceOf(ForgeException.class);
        var exempted = new AtomicReference<Order>();
        CurrentTenant.run(
                tenant, () -> TenantWriteGuard.exempt(() -> exempted.set(orders.create("例外写入", BigDecimal.ONE, null))));
        assertThat(exempted.get().getTitle()).isEqualTo("例外写入");

        setStatus(tenant, "trial");
        assertThat(call(tenant, HttpMethod.POST, Map.of("title", "试用期间", "amount", 1))
                        .get("httpStatus"))
                .isEqualTo(201);
    }

    @Test
    void tenantDataIsMarkedDeletedWhenCoolingOffEnds() {
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Order order = seed(tenant);
        Order otherOrder = seed(other);
        // 冷静期结束时租户处于删除状态，普通写入被拒；清理由 SDK 声明例外
        setStatus(tenant, "deleted");

        deliver(tenant, new TenantDeleted(tenant));
        // 重复投递也安全：已标记删除的行不再改动
        deliver(tenant, new TenantDeleted(tenant));

        Order cleaned = inTenant(tenant, () -> orderAudit.selectIncludingDeleted(order.getId()));
        assertThat(cleaned.getDeleted()).isTrue();
        assertThat(cleaned.getUpdatedBy()).isNull();
        assertThat(cleaned.getUpdatedAt()).isAfter(order.getUpdatedAt());
        assertThat(inTenant(tenant, orders::count)).isZero();
        assertThat(inTenant(tenant, () -> notifications.findByOrder(order.getId())))
                .isEmpty();
        assertThat(inTenant(tenant, categories::list)).isEmpty();
        // 其他租户的数据不受影响
        assertThat(inTenant(other, orders::count)).isEqualTo(1);
        assertThat(inTenant(other, () -> notifications.findByOrder(otherOrder.getId())))
                .hasSize(1);
        assertThat(inTenant(other, categories::list))
                .extracting(OrderCategory::getCode)
                .containsExactly("standard", "urgent");

        // TenantDeleted 必须带该租户的上下文发布：消费事务的 RLS 会话变量按消息头的租户设置，不能在处理器中换租户
        assertThatThrownBy(() -> deliver(other, new TenantDeleted(tenant))).isInstanceOf(IllegalStateException.class);
    }

    private Order seed(UUID tenant) {
        deliver(tenant, new TenantCreated(tenant, "待清理", "admin@example.com", "active"));
        Order order = inTenant(tenant, () -> orders.create("订单", BigDecimal.TEN, null));
        CurrentTenant.run(
                tenant,
                () -> notifications.handle(new OrderCreated(order.getId(), order.getTitle(), order.getAmount())));
        return order;
    }

    private void setStatus(UUID tenant, String status) {
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(tenant), status);
    }

    private static <T> T inTenant(UUID tenant, Supplier<T> task) {
        var result = new AtomicReference<T>();
        CurrentTenant.run(tenant, () -> result.set(task.get()));
        return result.get();
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

    private Map<String, Object> call(UUID tenant, HttpMethod method, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri("/orders")
                .header("Authorization", "Bearer " + TestTokens.forTenant(tenant));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(ENVELOPE));
    }
}
