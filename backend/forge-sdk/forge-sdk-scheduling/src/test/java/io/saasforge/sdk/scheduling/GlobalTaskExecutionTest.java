package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 通过 Spring 注解注册与真实 Redis 验证 Global Task；仅将触发时间替换为手动时钟。 */
class GlobalTaskExecutionTest {
    static final SharedContainers.Redis redisServer = SharedContainers.redis();

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        var configuration = new RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getMappedPort(6379));
        configuration.setDatabase(redisServer.database());
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterEach
    void clearLocks() {
        redis.delete(redis.keys("forge:task:execution-test:*"));
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    void onlyOneInstanceExecutesAndFurtherTriggersAreSkippedWhileItIsRunning() {
        var probe = new Probe();
        var firstScheduler = new ManualScheduler();
        var secondScheduler = new ManualScheduler();
        var firstMetrics = new SimpleMeterRegistry();
        var secondMetrics = new SimpleMeterRegistry();
        runner(firstScheduler, firstMetrics, new BlockingTask(probe))
                .run(first -> runner(secondScheduler, secondMetrics, new BlockingTask(probe))
                        .run(second -> {
                            assertThat(first).hasNotFailed();
                            assertThat(second).hasNotFailed();
                            var firstTrigger = CompletableFuture.runAsync(firstScheduler::fire);
                            var secondTrigger = CompletableFuture.runAsync(secondScheduler::fire);
                            try {
                                Awaitility.await()
                                        .atMost(Duration.ofSeconds(3))
                                        .untilAsserted(
                                                () -> assertThat(probe.starts).hasValue(1));
                                firstTrigger.get(3, TimeUnit.SECONDS);
                                secondTrigger.get(3, TimeUnit.SECONDS);
                                firstScheduler.fire();
                                secondScheduler.fire();
                                assertThat(probe.starts).hasValue(1);
                                assertThat(skips(firstMetrics) + skips(secondMetrics))
                                        .isEqualTo(3);
                            } finally {
                                probe.finish.countDown();
                            }
                            Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                                long count =
                                        firstMetrics
                                                        .find("forge.task.duration")
                                                        .tag("name", "blocking")
                                                        .timers()
                                                        .stream()
                                                        .mapToLong(timer -> timer.count())
                                                        .sum()
                                                + secondMetrics
                                                        .find("forge.task.duration")
                                                        .tag("name", "blocking")
                                                        .timers()
                                                        .stream()
                                                        .mapToLong(timer -> timer.count())
                                                        .sum();
                                assertThat(count).isEqualTo(1);
                            });
                        }));
    }

    @Test
    void renewsTheLockWhileATaskRunsLongerThanTheThirtySecondLease() {
        var probe = new Probe();
        var scheduler = new ManualScheduler();
        var contender = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        runner(scheduler, new SimpleMeterRegistry(), new BlockingTask(probe))
                .run(first -> runner(contender, metrics, new BlockingTask(probe))
                        .run(second -> {
                            scheduler.fire();
                            try {
                                Awaitility.await()
                                        .atMost(Duration.ofSeconds(3))
                                        .untilAsserted(
                                                () -> assertThat(probe.starts).hasValue(1));
                                Awaitility.await()
                                        .pollDelay(Duration.ofSeconds(31))
                                        .atMost(Duration.ofSeconds(33))
                                        .untilAsserted(() -> assertThat(redis.getExpire(
                                                        "forge:task:execution-test:blocking", TimeUnit.MILLISECONDS))
                                                .isPositive());
                                contender.fire();
                                assertThat(skips(metrics)).isEqualTo(1);
                                assertThat(probe.starts).hasValue(1);
                            } finally {
                                probe.finish.countDown();
                            }
                        }));
    }

    @Test
    void anotherOwnersTokenIsNeitherRenewedNorReleased() {
        var probe = new Probe();
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        String key = "forge:task:execution-test:blocking";
        runner(scheduler, metrics, new BlockingTask(probe)).run(context -> {
            scheduler.fire();
            try {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(3))
                        .untilAsserted(() -> assertThat(probe.starts).hasValue(1));
                assertThat(redis.opsForValue().get(key)).isNotBlank();
                redis.opsForValue().set(key, "another-owner", Duration.ofSeconds(12));
                Awaitility.await()
                        .pollDelay(Duration.ofSeconds(11))
                        .atMost(Duration.ofSeconds(11).plusMillis(500))
                        .untilAsserted(() -> {
                            assertThat(redis.opsForValue().get(key)).isEqualTo("another-owner");
                            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS))
                                    .isBetween(1L, 3000L);
                        });
            } finally {
                probe.finish.countDown();
            }
            Awaitility.await()
                    .during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        assertThat(metrics.get("forge.task.duration")
                                        .tag("name", "blocking")
                                        .timer()
                                        .count())
                                .isEqualTo(1);
                        assertThat(redis.opsForValue().get(key)).isEqualTo("another-owner");
                    });
        });
    }

    @Test
    void redisUnavailableSkipsTheTriggerAndCountsLockFailure() {
        var unavailable = new LettuceConnectionFactory("127.0.0.1", 1);
        unavailable.afterPropertiesSet();
        try {
            var scheduler = new ManualScheduler();
            var metrics = new SimpleMeterRegistry();
            var task = new SimpleTask();
            runner(scheduler, metrics, task, new StringRedisTemplate(unavailable))
                    .run(context -> {
                        scheduler.fire();
                        assertThat(task.executions).hasValue(0);
                        assertThat(metrics.get("forge.task.lock.failures")
                                        .tag("name", "simple")
                                        .counter()
                                        .count())
                                .isEqualTo(1);
                        assertThat(metrics.find("forge.task.executions").counters())
                                .isEmpty();
                    });
        } finally {
            unavailable.destroy();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discoversAnnotationsOnTheTargetAndInvokesThroughTheSpringProxy(boolean classProxy) {
        var scheduler = new ManualScheduler();
        var task = new SimpleTask();
        var advised = new AtomicInteger();
        var proxy = new ProxyFactory(task);
        proxy.setInterfaces(ScheduledOperation.class);
        proxy.setProxyTargetClass(classProxy);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            advised.incrementAndGet();
            return invocation.proceed();
        });
        runner(scheduler, new SimpleMeterRegistry(), proxy.getProxy()).run(context -> {
            assertThat(context).hasNotFailed();
            scheduler.fire();
            Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(advised).hasValue(1);
                assertThat(task.executions).hasValue(1);
            });
        });
    }

    @Test
    void failingExecutionIsRecordedAndTheNextTriggerCanRun() {
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var task = new FailingTask();
        runner(scheduler, metrics, task).run(context -> {
            scheduler.fire();
            Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(metrics.get("forge.task.duration")
                                .tag("name", "failing")
                                .timer()
                                .count())
                        .isEqualTo(1);
                assertThat(redis.hasKey("forge:task:execution-test:failing")).isFalse();
            });
            scheduler.fire();
            Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(task.executions).hasValue(2);
                assertThat(metrics.get("forge.task.executions")
                                .tag("name", "failing")
                                .counter()
                                .count())
                        .isEqualTo(2);
                assertThat(metrics.get("forge.task.duration")
                                .tag("name", "failing")
                                .timer()
                                .count())
                        .isEqualTo(2);
            });
        });
    }

    interface ScheduledOperation {
        void execute();
    }

    static class SimpleTask implements ScheduledOperation {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        @GlobalTask(name = "simple", fixedDelay = "1s")
        public void execute() {
            executions.incrementAndGet();
        }
    }

    static class FailingTask {
        final AtomicInteger executions = new AtomicInteger();

        @GlobalTask(name = "failing", fixedDelay = "1s")
        void execute() {
            if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("expected failure");
            }
        }
    }

    @Test
    void springSchedulingContinuesToTriggerWhileThePreviousExecutionIsRunning() {
        var metrics = new SimpleMeterRegistry();
        var probe = new Probe();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulingAutoConfiguration.class))
                .withBean(MeterRegistry.class, () -> metrics)
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(BlockingTask.class, () -> new BlockingTask(probe))
                .withPropertyValues(
                        "spring.application.name=execution-test",
                        "spring.data.redis.database=" + redisServer.database())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    try {
                        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                            assertThat(skips(metrics)).isGreaterThanOrEqualTo(1);
                            assertThat(probe.starts).hasValue(1);
                        });
                    } finally {
                        probe.finish.countDown();
                    }
                });
    }

    private static double skips(MeterRegistry metrics) {
        return metrics.find("forge.task.skipped").tag("name", "blocking").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private ApplicationContextRunner runner(ManualScheduler scheduler, MeterRegistry metrics, Object task) {
        return runner(scheduler, metrics, task, redis);
    }

    private ApplicationContextRunner runner(
            ManualScheduler scheduler, MeterRegistry metrics, Object task, StringRedisTemplate template) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulingAutoConfiguration.class))
                .withBean("forgeTaskScheduler", TaskScheduler.class, () -> scheduler)
                .withBean(MeterRegistry.class, () -> metrics)
                .withBean(StringRedisTemplate.class, () -> template)
                .withBean("task", Object.class, () -> task)
                .withPropertyValues(
                        "spring.application.name=execution-test",
                        "spring.data.redis.database=" + redisServer.database());
    }

    static class BlockingTask {
        final Probe probe;

        BlockingTask(Probe probe) {
            this.probe = probe;
        }

        @GlobalTask(name = "blocking", fixedDelay = "100ms")
        void execute() throws InterruptedException {
            probe.starts.incrementAndGet();
            probe.finish.await(60, TimeUnit.SECONDS);
        }
    }

    static class Probe {
        final AtomicInteger starts = new AtomicInteger();
        final CountDownLatch finish = new CountDownLatch(1);
    }

    static class ManualScheduler extends ThreadPoolTaskScheduler {
        Runnable trigger;

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            trigger = task;
            return null;
        }

        void fire() {
            trigger.run();
        }
    }
}
