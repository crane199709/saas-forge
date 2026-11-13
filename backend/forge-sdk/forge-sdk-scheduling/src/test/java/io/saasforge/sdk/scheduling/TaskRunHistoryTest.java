package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.tenant.TenantStatusLookup;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 使用公开调度声明和运行查询接口验证 Redis 中最近的 Task Run。 */
class TaskRunHistoryTest {
    static final SharedContainers.Redis server = SharedContainers.redis();
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        var configuration = new RedisStandaloneConfiguration(server.getHost(), server.getMappedPort(6379));
        configuration.setDatabase(server.database());
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterEach
    void cleanup() {
        redis.delete(redis.keys("forge:*:run-history-test:*"));
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    void corruptRunHistoryOnlyWarnsWhileTheTaskStillExecutesAndReleasesItsLock() {
        String currentKey = "forge:task-run:run-history-test:quick:current";
        redis.opsForValue().set(currentKey, "invalid-json");
        var logger = (Logger) LoggerFactory.getLogger(TaskRunHistory.class);
        var logs = new HistoryLogs();
        logs.start();
        logger.addAppender(logs);
        try {
            var scheduler = new ManualScheduler();
            var metrics = new SimpleMeterRegistry();
            var task = new QuickTask();
            task.fail = false;
            runner(scheduler, metrics, task).run(context -> {
                var history = context.getBean(TaskRunHistory.class);
                scheduler.fire();
                awaitCompleted(metrics, 1);
                assertThat(history.current("quick")).isEmpty();
                assertThat(logs.events)
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains("could not be started");
                        })
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains("could not be finished");
                        })
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains("could not be read");
                        });
                redis.delete(currentKey);
                scheduler.fire();
                awaitCompleted(metrics, 2);
                assertThat(history.recent("quick"))
                        .singleElement()
                        .satisfies(run -> assertThat(run.status()).isEqualTo(TaskRun.Status.SUCCEEDED));
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"all-failed", "all-succeeded", "empty", "directory-unavailable"})
    void tenantRunDistinguishesWholeRoundResults(String scenario) throws Exception {
        List<UUID> tenants =
                scenario.equals("empty") ? List.of() : List.of(new UUID(0, scenario.equals("all-succeeded") ? 102 : 1));
        var iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/tenants", exchange -> {
            String ids = tenants.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
            byte[] body = ("{\"code\":\"ok\",\"httpStatus\":200,\"data\":[" + ids
                            + "],\"meta\":{\"page\":{\"number\":1,\"size\":100,\"totalElements\":" + tenants.size()
                            + ",\"totalPages\":1}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(scenario.equals("directory-unavailable") ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
        try {
            var scheduler = new ManualScheduler();
            runner(scheduler, new SimpleMeterRegistry(), new TenantProbe())
                    .withBean(TenantStatusLookup.class, () -> id -> "active")
                    .withPropertyValues(
                            "forge.scheduling.iam-uri=http://127.0.0.1:"
                                    + iam.getAddress().getPort(),
                            "forge.scheduling.token=service-token")
                    .run(context -> {
                        var history = context.getBean(TaskRunHistory.class);
                        scheduler.fire();
                        boolean failed = scenario.equals("all-failed") || scenario.equals("directory-unavailable");
                        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                            assertThat(history.recent("tenant")).singleElement().satisfies(run -> {
                                assertThat(run.status())
                                        .isEqualTo(failed ? TaskRun.Status.FAILED : TaskRun.Status.SUCCEEDED);
                                assertThat(run.successCount()).isEqualTo(scenario.equals("all-succeeded") ? 1 : 0);
                                assertThat(run.failureCount()).isEqualTo(scenario.equals("all-failed") ? 1 : 0);
                                if (failed) {
                                    assertThat(run.errorSummary()).isNotBlank();
                                } else {
                                    assertThat(run.errorSummary()).isNull();
                                }
                            });
                            assertThat(history.problems("tenant")).hasSize(failed ? 1 : 0);
                        });
                    });
        } finally {
            iam.stop(0);
        }
    }

    @Test
    void abandonedRunIsInterruptedAndArchivedWithoutAnOldOwnerRemovingTheNewCurrent() {
        var firstScheduler = new ManualScheduler();
        var secondScheduler = new ManualScheduler();
        var firstTask = new GlobalProbe();
        var secondTask = new GlobalProbe();
        var firstMetrics = new SimpleMeterRegistry();
        runner(firstScheduler, firstMetrics, firstTask).run(first -> {
            var history = first.getBean(TaskRunHistory.class);
            firstScheduler.fire();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(firstTask.executions).hasValue(1));
            UUID abandonedId = history.current("global").orElseThrow().id();
            assertThat(redis.getExpire("forge:task-run:run-history-test:global:current", TimeUnit.SECONDS))
                    .isBetween(
                            Duration.ofDays(89).toSeconds(), Duration.ofDays(90).toSeconds());
            redis.delete("forge:task:run-history-test:global");
            assertThat(history.current("global").orElseThrow().status()).isEqualTo(TaskRun.Status.INTERRUPTED);
            runner(secondScheduler, new SimpleMeterRegistry(), secondTask).run(second -> {
                secondScheduler.fire();
                try {
                    Awaitility.await()
                            .atMost(Duration.ofSeconds(3))
                            .untilAsserted(
                                    () -> assertThat(secondTask.executions).hasValue(1));
                    UUID newId = history.current("global").orElseThrow().id();
                    assertThat(newId).isNotEqualTo(abandonedId);
                    assertThat(history.problems("global")).singleElement().satisfies(run -> {
                        assertThat(run.id()).isEqualTo(abandonedId);
                        assertThat(run.status()).isEqualTo(TaskRun.Status.INTERRUPTED);
                    });
                    firstTask.finish.countDown();
                    Awaitility.await()
                            .during(Duration.ofMillis(150))
                            .atMost(Duration.ofSeconds(3))
                            .untilAsserted(() -> {
                                var timer = firstMetrics
                                        .find("forge.task.duration")
                                        .tag("name", "global")
                                        .timer();
                                assertThat(timer).isNotNull();
                                assertThat(timer.count()).isEqualTo(1);
                                assertThat(history.current("global")
                                                .orElseThrow()
                                                .id())
                                        .isEqualTo(newId);
                                assertThat(history.current("global")
                                                .orElseThrow()
                                                .status())
                                        .isEqualTo(TaskRun.Status.RUNNING);
                            });
                } finally {
                    firstTask.finish.countDown();
                    secondTask.finish.countDown();
                }
                Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertThat(history.current("global")).isEmpty();
                    assertThat(history.recent("global")).hasSize(2);
                    assertThat(history.problems("global")).hasSize(1);
                });
            });
        });
    }

    @Test
    void boundedRecentHistoryKeepsFailuresAcrossManySuccessesAndRefreshesNinetyDayTtl() {
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var task = new QuickTask();
        runner(scheduler, metrics, task).run(context -> {
            var history = context.getBean(TaskRunHistory.class);
            for (int i = 1; i <= 102; i++) {
                scheduler.fire();
                awaitCompleted(metrics, i);
            }
            assertThat(history.recent("quick")).hasSize(100).allSatisfy(run -> {
                assertThat(run.status()).isEqualTo(TaskRun.Status.FAILED);
                assertThat(run.errorSummary()).isEqualTo("java.lang.IllegalStateException");
            });
            assertThat(history.problems("quick")).hasSize(100);
            assertThat(redis.opsForList().size("forge:task-run:run-history-test:quick:recent"))
                    .isEqualTo(100);
            assertThat(redis.opsForList().size("forge:task-run:run-history-test:quick:problems"))
                    .isEqualTo(100);
            var problemIds = history.problems("quick").stream().map(TaskRun::id).toList();
            redis.expire("forge:task-run:run-history-test:quick:problems", Duration.ofHours(1));
            task.fail = false;
            for (int i = 103; i <= 203; i++) {
                scheduler.fire();
                awaitCompleted(metrics, i);
            }
            assertThat(history.recent("quick"))
                    .hasSize(100)
                    .allSatisfy(run -> assertThat(run.status()).isEqualTo(TaskRun.Status.SUCCEEDED));
            assertThat(history.problems("quick").stream().map(TaskRun::id)).containsExactlyElementsOf(problemIds);
            for (String suffix : List.of("recent", "problems")) {
                assertThat(redis.getExpire("forge:task-run:run-history-test:quick:" + suffix, TimeUnit.SECONDS))
                        .isBetween(
                                Duration.ofDays(89).toSeconds(),
                                Duration.ofDays(90).toSeconds());
            }
        });
    }

    private void awaitCompleted(MeterRegistry metrics, int count) {
        Awaitility.await()
                .pollInterval(Duration.ofMillis(5))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    var counter = metrics.find("forge.task.executions")
                            .tag("name", "quick")
                            .counter();
                    assertThat(counter).isNotNull();
                    assertThat(counter.count()).isEqualTo(count);
                    assertThat(redis.hasKey("forge:task:run-history-test:quick"))
                            .isFalse();
                });
    }

    @Test
    void tenantRunCountsAllFailuresCapsDetailsAndKeepsSensitiveMessagesOutOfRedis() throws Exception {
        List<UUID> tenants =
                IntStream.rangeClosed(1, 103).mapToObj(i -> new UUID(0, i)).toList();
        var iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/tenants", exchange -> {
            int page = exchange.getRequestURI().getQuery().contains("page=2") ? 2 : 1;
            List<UUID> items = page == 1 ? tenants.subList(0, 100) : tenants.subList(100, 103);
            String ids = items.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
            byte[] body = ("{\"code\":\"ok\",\"httpStatus\":200,\"data\":[" + ids + "],\"meta\":{\"page\":{\"number\":"
                            + page + ",\"size\":100,\"totalElements\":103,\"totalPages\":2}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
        try {
            var scheduler = new ManualScheduler();
            runner(scheduler, new SimpleMeterRegistry(), new TenantProbe())
                    .withBean(
                            TenantStatusLookup.class, () -> id -> id.equals(tenants.get(102)) ? "suspended" : "active")
                    .withPropertyValues(
                            "forge.scheduling.iam-uri=http://127.0.0.1:"
                                    + iam.getAddress().getPort(),
                            "forge.scheduling.token=service-token")
                    .run(context -> {
                        var history = context.getBean(TaskRunHistory.class);
                        scheduler.fire();
                        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                            assertThat(history.recent("tenant")).singleElement().satisfies(run -> {
                                assertThat(run.type()).isEqualTo(TaskRun.Type.TENANT);
                                assertThat(run.status()).isEqualTo(TaskRun.Status.PARTIALLY_FAILED);
                                assertThat(run.successCount()).isEqualTo(1);
                                assertThat(run.failureCount()).isEqualTo(101);
                                assertThat(run.failedTenants()).hasSize(100);
                                assertThat(run.failedTenants().get(0).errorSummary())
                                        .contains("ForgeException", "request.invalid");
                            });
                            assertThat(history.problems("tenant")).hasSize(1);
                        });
                        // 该探针只检查敏感信息落盘约束，运行行为始终通过公开查询接口验证。
                        redis.keys("forge:task-run:run-history-test:*").forEach(key -> {
                            if (key.endsWith("current")) {
                                assertThat(redis.opsForValue().get(key)).doesNotContain("secret-account-42");
                            } else {
                                assertThat(redis.opsForList().range(key, 0, -1))
                                        .allSatisfy(value -> assertThat(value).doesNotContain("secret-account-42"));
                            }
                        });
                    });
        } finally {
            iam.stop(0);
        }
    }

    @Test
    void globalRunMovesFromCurrentToRecentAndSkippedTriggersDoNotProduceRuns() {
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var task = new GlobalProbe();
        runner(scheduler, metrics, task).run(context -> {
            assertThat(context).hasNotFailed();
            var history = context.getBean(TaskRunHistory.class);
            scheduler.fire();
            try {
                Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    var current = history.current("global").orElseThrow();
                    assertThat(current.type()).isEqualTo(TaskRun.Type.GLOBAL);
                    assertThat(current.trigger()).isEqualTo(TaskRun.Trigger.SCHEDULED);
                    assertThat(current.status()).isEqualTo(TaskRun.Status.RUNNING);
                    assertThat(current.startedAt()).isNotNull();
                    assertThat(current.finishedAt()).isNull();
                    assertThat(current.operatorId()).isNull();
                    assertThat(current.targetTenantId()).isNull();
                    assertThat(task.executions).hasValue(1);
                });
                scheduler.fire();
                assertThat(metrics.get("forge.task.skipped")
                                .tag("name", "global")
                                .counter()
                                .count())
                        .isEqualTo(1);
                assertThat(history.recent("global")).isEmpty();
            } finally {
                task.finish.countDown();
            }
            Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(history.current("global")).isEmpty();
                assertThat(history.recent("global")).singleElement().satisfies(run -> {
                    assertThat(run.status()).isEqualTo(TaskRun.Status.SUCCEEDED);
                    assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
                });
                assertThat(history.problems("global")).isEmpty();
                assertThat(metrics.get("forge.task.executions")
                                .tag("name", "global")
                                .counter()
                                .count())
                        .isEqualTo(1);
            });
        });
    }

    private ApplicationContextRunner runner(ManualScheduler scheduler, MeterRegistry metrics, Object task) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulingAutoConfiguration.class))
                .withBean("forgeTaskScheduler", TaskScheduler.class, () -> scheduler)
                .withBean(MeterRegistry.class, () -> metrics)
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean("task", Object.class, () -> task)
                .withPropertyValues(
                        "spring.application.name=run-history-test", "spring.data.redis.database=" + server.database());
    }

    static class HistoryLogs extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    }

    static class QuickTask {
        volatile boolean fail = true;

        @GlobalTask(name = "quick", fixedDelay = "1s")
        void execute() {
            if (fail) {
                throw new IllegalStateException("secret-account-42");
            }
        }
    }

    static class TenantProbe {
        @TenantTask(name = "tenant", fixedDelay = "1s")
        void execute() {
            if (CurrentTenant.require().getLeastSignificantBits() <= 101) {
                throw ForgeException.withErrorMessage(FrameworkErrorCode.REQUEST_INVALID, "secret-account-42");
            }
        }
    }

    static class GlobalProbe {
        final AtomicInteger executions = new AtomicInteger();
        final CountDownLatch finish = new CountDownLatch(1);

        @GlobalTask(name = "global", fixedDelay = "1s")
        void execute() throws InterruptedException {
            executions.incrementAndGet();
            finish.await(10, TimeUnit.SECONDS);
        }
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
