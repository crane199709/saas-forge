package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.numbering.BusinessNumbers;
import io.saasforge.sdk.numbering.DateSegment;
import io.saasforge.sdk.numbering.NumberingRule;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business Number（字典、参数与编号规格第 5 节）：在业务事务内加锁递增，连续不跳号，日期段与计数周期按 Business Time Zone 计算。
 *
 * <p>测试配置的 Business Time Zone 为 {@code Asia/Shanghai}；时钟由测试控制，以便跨越计数周期。
 */
@SpringBootTest
@Import(BusinessNumberTest.Clocks.class)
class BusinessNumberTest {
    static final NumberingRule DAILY = new NumberingRule("test-daily", "T", DateSegment.DAY, 3);
    static final NumberingRule PLAIN = new NumberingRule("test-plain", "N", null, 1);
    // 北京时间 2026-11-04 10:00
    static final Instant MORNING = Instant.parse("2026-11-04T02:00:00Z");

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

    @Autowired
    BusinessNumbers numbers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    MutableClock clock;

    @Autowired
    List<EventHandler<?>> handlers;

    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void resetClock() {
        clock.set(MORNING);
    }

    @Test
    void concurrentNumbersAreConsecutiveAndUnique() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit((Callable<String>) () -> take(tenant, DAILY)));
            }
            List<String> taken = new ArrayList<>();
            for (Future<String> future : futures) {
                taken.add(future.get());
            }
            assertThat(taken)
                    .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 40)
                            .mapToObj(n -> "T-20261104-%03d".formatted(n))
                            .toList());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rolledBackNumberIsTakenAgainSoNoGapIsLeft() {
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261104-001");

        var discarded = new AtomicReference<String>();
        CurrentTenant.run(
                tenant,
                () -> transaction.executeWithoutResult(status -> {
                    discarded.set(numbers.next(DAILY));
                    status.setRollbackOnly();
                }));
        assertThat(discarded.get()).isEqualTo("T-20261104-002");

        assertThat(take(tenant, DAILY)).isEqualTo("T-20261104-002");
    }

    @Test
    void counterResetsWhenTheBusinessDayChangesInBusinessZone() {
        // 北京时间 2026-11-03 23:59:59，UTC 仍是 11-03 15:59:59
        clock.set(Instant.parse("2026-11-03T15:59:59Z"));
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261103-001");
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261103-002");
        // 北京时间已是 11-04，UTC 日期未变：按服务器时区或 UTC 计算都不会重置
        clock.set(Instant.parse("2026-11-03T16:00:00Z"));
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261104-001");
    }

    @Test
    void sequenceGrowsBeyondMinimumDigitsAndRuleWithoutDateNeverResets() {
        List<String> taken = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            taken.add(take(tenant, PLAIN));
        }
        clock.set(MORNING.plusSeconds(400L * 24 * 3600));
        taken.add(take(tenant, PLAIN));
        assertThat(taken).startsWith("N-1", "N-2").endsWith("N-9", "N-10");
    }

    @Test
    void countersArePerTenant() {
        UUID other = UUID.randomUUID();
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261104-001");
        assertThat(take(other, DAILY)).isEqualTo("T-20261104-001");
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261104-002");
    }

    @Test
    void numbersMustBeTakenInsideTheBusinessTransaction() {
        assertThatThrownBy(() -> CurrentTenant.run(tenant, () -> numbers.next(DAILY)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tenantCountersAreMarkedDeletedWhenTenantIsDeleted() {
        UUID other = UUID.randomUUID();
        take(tenant, DAILY);
        take(tenant, DAILY);
        take(other, DAILY);

        deliver(tenant, new TenantDeleted(tenant));

        // 计数行已被逻辑删除，不再参与计数
        assertThat(take(tenant, DAILY)).isEqualTo("T-20261104-001");
        assertThat(take(other, DAILY)).isEqualTo("T-20261104-002");
    }

    private String take(UUID tenantId, NumberingRule rule) {
        return inTenant(tenantId, () -> transaction.execute(status -> numbers.next(rule)));
    }

    private static <T> T inTenant(UUID tenantId, Supplier<T> task) {
        var result = new AtomicReference<T>();
        CurrentTenant.run(tenantId, () -> result.set(task.get()));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = MORNING;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
