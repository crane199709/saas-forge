package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderAuditMapper;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** 经 Spring 注册、真实 Redis 与订单 HTTP 接口验收 Tenant Task 的业务结果。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OrderAutoCloseTest.Scheduling.class)
class OrderAutoCloseTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000051");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    ManualScheduler scheduler;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    OrderAuditMapper orders;

    @Autowired
    MeterRegistry metrics;

    @AfterEach
    void clearDirectory() {
        TestReferenceData.tenants(List.of());
    }

    @Test
    void registeredTaskClosesOnlyExpiredUnpaidOrders() throws Exception {
        active(TENANT);
        TestReferenceData.tenants(List.of(TENANT));
        UUID expired = create(TENANT, "超时订单", "2026-11-06T11:00:00Z");
        UUID fresh = create(TENANT, "未到期订单", "2026-11-06T11:45:00Z");
        UUID boundary = create(TENANT, "恰好到期订单", "2026-11-06T11:30:00Z");
        UUID deleted = create(TENANT, "已删除订单", "2026-11-06T11:00:00Z");
        exchange(TENANT, HttpMethod.DELETE, "/orders/" + deleted, null);
        UUID outside = UUID.fromString("019a0110-0000-7000-8000-000000000052");
        active(outside);
        UUID unlisted = create(outside, "目录外租户订单", "2026-11-06T11:00:00Z");
        Audit before = audit(TENANT, expired);
        Audit freshBefore = audit(TENANT, fresh);
        Audit boundaryBefore = audit(TENANT, boundary);
        Audit deletedBefore = audit(TENANT, deleted);
        assertThat(before.updatedBy()).isEqualTo(TestTokens.IDENTITY_ID);

        assertThat(scheduler.trigger).as("Spring 已注册 order.auto-close cron 任务").isNotNull();
        assertThat(scheduler.cron.getExpression()).isEqualTo("0 */5 * * * *");
        runTask();

        assertThat(get(TENANT, expired).get("status")).isEqualTo("closed");
        assertThat(get(TENANT, fresh).get("status")).isEqualTo("unpaid");
        assertThat(get(TENANT, boundary).get("status")).isEqualTo("unpaid");
        assertThat(get(outside, unlisted).get("status")).isEqualTo("unpaid");
        Audit closed = audit(TENANT, expired);
        assertThat(closed.updatedBy()).isNull();
        assertThat(closed.updatedAt()).isAfter(before.updatedAt());
        assertThat(closed.createdAt()).isEqualTo(before.createdAt());
        assertThat(closed.createdBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(audit(TENANT, fresh)).isEqualTo(freshBefore);
        assertThat(audit(TENANT, boundary)).isEqualTo(boundaryBefore);
        assertThat(audit(TENANT, deleted)).isEqualTo(deletedBefore);

        runTask();

        assertThat(audit(TENANT, expired)).isEqualTo(closed);
        assertThat(audit(TENANT, fresh)).isEqualTo(freshBefore);
        assertThat(audit(TENANT, boundary)).isEqualTo(boundaryBefore);
        assertThat(audit(TENANT, deleted)).isEqualTo(deletedBefore);
    }

    @Test
    void tenantTimeoutsApplyAndParameterFailureDoesNotBlockLaterTenants() throws Exception {
        UUID shortTimeout = UUID.fromString("019a0110-0000-7000-8000-000000000053");
        UUID failure = UUID.fromString("019a0110-0000-7000-8000-000000000054");
        UUID longTimeout = UUID.fromString("019a0110-0000-7000-8000-000000000055");
        UUID later = UUID.fromString("019a0110-0000-7000-8000-000000000056");
        for (UUID tenant : List.of(shortTimeout, failure, longTimeout, later)) active(tenant);
        TestReferenceData.autoClose(shortTimeout, Duration.ofMinutes(15));
        TestReferenceData.autoClose(longTimeout, Duration.ofMinutes(30));
        TestReferenceData.autoClose(later, Duration.ofMinutes(10));
        UUID firstOrder = create(shortTimeout, "短阈值订单", "2026-11-06T11:40:00Z");
        UUID failedOrder = create(failure, "参数故障订单", "2026-11-06T11:00:00Z");
        UUID longOrder = create(longTimeout, "长阈值订单", "2026-11-06T11:40:00Z");
        UUID lastOrder = create(later, "故障后的租户订单", "2026-11-06T11:40:00Z");
        TestReferenceData.fail(failure);
        TestReferenceData.tenants(List.of(shortTimeout, failure, longTimeout, later));
        double failuresBefore = failures();

        runTask();

        assertThat(get(shortTimeout, firstOrder).get("status")).isEqualTo("closed");
        assertThat(get(failure, failedOrder).get("status")).isEqualTo("unpaid");
        assertThat(get(longTimeout, longOrder).get("status")).isEqualTo("unpaid");
        assertThat(get(later, lastOrder).get("status")).isEqualTo("closed");
        assertThat(failures()).isEqualTo(failuresBefore + 1);
    }

    private void active(UUID tenant) {
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(tenant), "active");
    }

    private double failures() {
        var counter = metrics.find("forge.task.tenant.failures")
                .tag("name", "order.auto-close")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private void runTask() {
        var timer = metrics.find("forge.task.duration")
                .tag("name", "order.auto-close")
                .timer();
        long completed = timer == null ? 0 : timer.count();
        scheduler.trigger.run();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // 计时器在任务结束时才注册，首次执行尚未完成时应继续等待。
            var completedTimer = metrics.find("forge.task.duration")
                    .tag("name", "order.auto-close")
                    .timer();
            assertThat(completedTimer).isNotNull();
            assertThat(completedTimer.count()).isEqualTo(completed + 1);
        });
    }

    private Audit audit(UUID tenant, UUID id) {
        var row = new AtomicReference<Order>();
        CurrentTenant.run(tenant, () -> row.set(orders.selectIncludingDeleted(id)));
        Order order = row.get();
        return new Audit(
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getCreatedBy(),
                order.getUpdatedBy(),
                order.getDeleted());
    }

    record Audit(
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID createdBy,
            UUID updatedBy,
            boolean deleted) {}

    private UUID create(UUID tenant, String title, String createdAt) throws Exception {
        Map<?, ?> data = (Map<?, ?>) exchange(tenant, HttpMethod.POST, "/orders", Map.of("title", title, "amount", 10))
                .get("data");
        assertThat(data.get("status")).isEqualTo("unpaid");
        UUID id = UUID.fromString((String) data.get("id"));
        CurrentTenant.run(tenant, () -> orders.setCreatedAt(id, OffsetDateTime.parse(createdAt)));
        // 经真实请求重新写入成员审计，才能证明任务会把最后操作人清为空。
        exchange(tenant, HttpMethod.PUT, "/orders/" + id, Map.of("title", title, "amount", 10));
        return id;
    }

    private Map<?, ?> get(UUID tenant, UUID id) throws Exception {
        return (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/" + id, null).get("data");
    }

    private Map<String, Object> exchange(UUID tenant, HttpMethod method, String uri, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(tenant));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, response) -> {
            Map<String, Object> envelope = response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            return envelope;
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Scheduling {
        @Bean
        Clock orderClock() {
            return Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean(name = "forgeTaskScheduler")
        ManualScheduler scheduler() {
            return new ManualScheduler();
        }
    }

    static class ManualScheduler extends ThreadPoolTaskScheduler {
        Runnable trigger;
        CronTrigger cron;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            this.trigger = task;
            this.cron = (CronTrigger) trigger;
            return null;
        }
    }
}
