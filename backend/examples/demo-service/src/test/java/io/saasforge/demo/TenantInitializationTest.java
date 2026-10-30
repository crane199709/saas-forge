package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.saasforge.demo.category.entity.OrderCategory;
import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.demo.category.service.OrderCategoryService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializationReported;
import io.saasforge.sdk.provisioning.TenantInitializationRequested;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * demo-service 经 SDK 的租户初始化扩展点参与 Provisioning：幂等初始化、回报结果，失败后可按服务重试。
 *
 * <p>按消费端的方式调用 SDK 注册的事件处理器（恢复租户上下文并开启事务），不经 RocketMQ；
 * 同一事件 ID 的去重由 forge-sdk-mq 负责，已在 {@link OutboxMessagingTest} 验证，这里验证重复的初始化本身不产生重复数据。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantInitializationTest {
    static final String SERVICE = "demo-service";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        TestTokens.register(registry);
    }

    @MockitoSpyBean
    OrderCategoryService categories;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void repeatedInitializationKeepsOneCopyOfDefaultsAndReportsSuccess() {
        TenantCreated tenant = tenant();

        deliver(tenant.tenantId(), tenant);
        deliver(tenant.tenantId(), tenant);

        assertThat(categoryCodes(tenant.tenantId())).containsExactly("standard", "urgent");
        assertThat(reports(tenant.tenantId()))
                .containsExactly(
                        new TenantInitializationReported(tenant.tenantId(), SERVICE, true, null),
                        new TenantInitializationReported(tenant.tenantId(), SERVICE, true, null));
        // 预置数据按租户隔离：其他租户看不到
        assertThat(categoryCodes(UUID.randomUUID())).isEmpty();
    }

    @Test
    void failedInitializationIsRolledBackAndReportedThenRetrySucceeds() {
        TenantCreated tenant = tenant();
        // 写入部分数据后失败：保存点回滚这部分数据，失败回报照常提交
        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("simulated failure");
                })
                .when(categories)
                .initialize(any());

        deliver(tenant.tenantId(), tenant);

        assertThat(categoryCodes(tenant.tenantId())).isEmpty();
        List<TenantInitializationReported> failed = reports(tenant.tenantId());
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).succeeded()).isFalse();
        assertThat(failed.get(0).error()).contains("simulated failure");

        doAnswer(invocation -> invocation.callRealMethod()).when(categories).initialize(any());
        // 发给其他服务的重试请求不触发本服务初始化
        deliver(tenant.tenantId(), new TenantInitializationRequested("forge-iam", tenant));
        assertThat(reports(tenant.tenantId())).hasSize(1);

        deliver(tenant.tenantId(), new TenantInitializationRequested(SERVICE, tenant));

        assertThat(categoryCodes(tenant.tenantId())).containsExactly("standard", "urgent");
        assertThat(reports(tenant.tenantId()))
                .last()
                .isEqualTo(new TenantInitializationReported(tenant.tenantId(), SERVICE, true, null));
    }

    private static TenantCreated tenant() {
        return new TenantCreated(UUID.randomUUID(), "测试租户", "admin@example.com", "active");
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

    private List<String> categoryCodes(UUID tenantId) {
        var codes = new AtomicReference<List<String>>();
        CurrentTenant.run(
                tenantId,
                () -> codes.set(
                        categories.list().stream().map(OrderCategory::getCode).toList()));
        return codes.get();
    }

    private List<TenantInitializationReported> reports(UUID tenantId) {
        return outbox.payloads(tenantId, TenantInitializationReported.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, TenantInitializationReported.class))
                .toList();
    }
}
