package io.saasforge.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 集成测试基类：同一测试 JVM 内只启动一个 PostgreSQL 容器、只创建一个 Spring 上下文。
 *
 * <p>各测试类原先各自声明容器与 {@code @DynamicPropertySource}，数据源地址与属性来源都不同，Spring 无法复用上下文，
 * 每个类都要重新启动容器、执行迁移、启动应用。配置集中在此后上下文缓存键一致，后续测试类直接复用。
 *
 * <p>容器不随测试类停止，JVM 退出后由 Testcontainers 回收。测试类共用同一个库，须自行避免依赖库中只有本类数据。
 */
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PlatformIntegrationTest.SchedulingClock.class)
abstract class PlatformIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    static {
        POSTGRES.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulingClock {
        @Bean("forgeTaskScheduler")
        PlatformTaskScheduler scheduler() {
            return new PlatformTaskScheduler();
        }
    }

    /** 测试显式推进时间，避免生命周期后台扫描干扰共享数据库中的业务断言。 */
    static class PlatformTaskScheduler extends ThreadPoolTaskScheduler {
        Runnable scan;
        Duration interval;

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            scan = task;
            interval = delay;
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return scheduleWithFixedDelay(task, delay);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        PlatformTokens.register(registry);
    }
}
