package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.security.JwtCurrentActorProvider;
import io.saasforge.sdk.security.TenantContext;
import io.saasforge.sdk.tenant.AuditableEntity;
import io.saasforge.sdk.tenant.TenantAutoConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 仅替换外部 IAM 与触发时间，通过 Spring 注解和真实 Redis 验证 Tenant Task。 */
class TenantTaskExecutionTest {
    static final SharedContainers.Redis redisServer = SharedContainers.redis();
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    HttpServer iam;
    final AtomicInteger directoryRequests = new AtomicInteger();

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(redisConfiguration());
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    static RedisStandaloneConfiguration redisConfiguration() {
        var configuration = new RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getMappedPort(6379));
        configuration.setDatabase(redisServer.database());
        return configuration;
    }

    @AfterEach
    void cleanup() {
        if (iam != null) {
            iam.stop(0);
        }
        redis.delete(redis.keys("*"));
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    void fetchesAllPagesAndIsolatesATenantFailureWithinSequentialTenantContexts() throws Exception {
        List<UUID> tenants =
                IntStream.rangeClosed(1, 101).mapToObj(i -> new UUID(0, i)).toList();
        tenants.forEach(id -> redis.opsForValue().set(MembershipStateKeys.tenantStatus(id), "active"));
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(tenants.get(100)), "trial");
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/tenants", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            if (!"Bearer service-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            int page = exchange.getRequestURI().getQuery().contains("page=2") ? 2 : 1;
            List<UUID> items = page == 1 ? tenants.subList(0, 100) : tenants.subList(100, 101);
            byte[] body = page(items, page, 101).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var task = new TenantProbe(tenants.get(1));
        var logs = new TenantFailureLogs();
        var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        logger.addAppender(logs);
        try {
            runner(scheduler, metrics, task).run(context -> {
                assertThat(context).hasNotFailed();
                scheduler.fire();
                Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(metrics.find("forge.task.duration")
                                    .tag("name", "tenant-flow")
                                    .timer())
                            .isNotNull();
                    assertThat(metrics.get("forge.task.duration")
                                    .tag("name", "tenant-flow")
                                    .timer()
                                    .count())
                            .isEqualTo(1);
                    assertThat(task.attempts).containsExactlyElementsOf(tenants);
                    assertThat(task.threads.stream().distinct()).hasSize(1);
                    assertThat(metrics.get("forge.task.tenant.successes")
                                    .tag("name", "tenant-flow")
                                    .counter()
                                    .count())
                            .isEqualTo(100);
                    assertThat(metrics.get("forge.task.tenant.failures")
                                    .tag("name", "tenant-flow")
                                    .counter()
                                    .count())
                            .isEqualTo(1);
                    assertThat(logs.events).anySatisfy(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains(tenants.get(1).toString());
                        assertThat(event.getMDCPropertyMap())
                                .containsEntry(
                                        CurrentTenant.MDC_KEY, tenants.get(1).toString());
                    });
                });
                assertThat(requests)
                        .containsExactly(
                                "/internal/tenants?status=trial,active&page=1&size=100",
                                "/internal/tenants?status=trial,active&page=2&size=100");
                assertThat(CurrentTenant.find()).isEmpty();
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    static class TenantFailureLogs extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getFormattedMessage().startsWith("Tenant Task tenant-flow failed")) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        }
    }

    @Test
    void skipsSuspendedExpiredUnknownAndTenantsSuspendedAfterTheDirectoryWasFetched() throws Exception {
        UUID first = new UUID(0, 201);
        UUID suspended = new UUID(0, 202);
        UUID expired = new UUID(0, 203);
        UUID unpublished = new UUID(0, 204);
        UUID suspendedDuringRun = new UUID(0, 205);
        List<UUID> tenants = List.of(first, suspended, expired, unpublished, suspendedDuringRun);
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(first), "active");
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(suspended), "suspended");
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(expired), "expired");
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(suspendedDuringRun), "active");
        serve(tenants);
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var task = new StateChangingTask(suspendedDuringRun);
        runner(scheduler, metrics, task).run(context -> {
            assertThat(context).hasNotFailed();
            scheduler.fire();
            Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(metrics.find("forge.task.duration")
                                .tag("name", "tenant-flow")
                                .timer())
                        .isNotNull();
                assertThat(task.attempts).containsExactly(first);
                assertThat(metrics.get("forge.task.tenant.successes")
                                .tag("name", "tenant-flow")
                                .counter()
                                .count())
                        .isEqualTo(1);
                assertThat(metrics.find("forge.task.tenant.failures").counters())
                        .isEmpty();
            });
        });
    }

    @Test
    void concurrentInstancesSkipTheWholeRoundAndTheNextRoundFetchesAFreshDirectory() throws Exception {
        UUID tenant = new UUID(0, 301);
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(tenant), "active");
        serve(List.of(tenant));
        var firstScheduler = new ManualScheduler();
        var secondScheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var task = new BlockingTenantTask();
        runner(firstScheduler, metrics, task)
                .run(first -> runner(secondScheduler, metrics, task).run(second -> {
                    assertThat(first).hasNotFailed();
                    assertThat(second).hasNotFailed();
                    firstScheduler.fire();
                    try {
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(3))
                                .untilAsserted(() -> assertThat(task.starts).hasValue(1));
                        secondScheduler.fire();
                        firstScheduler.fire();
                        assertThat(task.starts).hasValue(1);
                        assertThat(directoryRequests).hasValue(1);
                        assertThat(metrics.get("forge.task.skipped")
                                        .tag("name", "tenant-blocking")
                                        .counter()
                                        .count())
                                .isEqualTo(2);
                    } finally {
                        task.finish.countDown();
                    }
                    Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                        assertThat(metrics.find("forge.task.duration")
                                        .tag("name", "tenant-blocking")
                                        .timer())
                                .isNotNull();
                        assertThat(redis.hasKey("forge:task:tenant-execution-test:tenant-blocking"))
                                .isFalse();
                    });
                    secondScheduler.fire();
                    Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                        assertThat(metrics.get("forge.task.duration")
                                        .tag("name", "tenant-blocking")
                                        .timer()
                                        .count())
                                .isEqualTo(2);
                        assertThat(task.starts).hasValue(2);
                        assertThat(directoryRequests).hasValue(2);
                    });
                }));
    }

    static class BlockingTenantTask {
        final AtomicInteger starts = new AtomicInteger();
        final CountDownLatch finish = new CountDownLatch(1);

        @TenantTask(name = "tenant-blocking", fixedDelay = "1s")
        void execute() throws InterruptedException {
            CurrentTenant.require();
            starts.incrementAndGet();
            finish.await(10, TimeUnit.SECONDS);
        }
    }

    void serve(List<UUID> tenants) throws Exception {
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/tenants", exchange -> {
            directoryRequests.incrementAndGet();
            byte[] body = page(tenants, 1, tenants.size()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
    }

    static class StateChangingTask {
        final UUID suspendedDuringRun;
        final List<UUID> attempts = Collections.synchronizedList(new ArrayList<>());

        StateChangingTask(UUID suspendedDuringRun) {
            this.suspendedDuringRun = suspendedDuringRun;
        }

        @TenantTask(name = "tenant-flow", fixedDelay = "1s")
        void execute() {
            attempts.add(CurrentTenant.require());
            redis.opsForValue().set(MembershipStateKeys.tenantStatus(suspendedDuringRun), "suspended");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invokesTheSpringProxyWithTenantButWithoutRequestMembershipOrActor(boolean classProxy) throws Exception {
        UUID tenant = new UUID(0, 401);
        UUID callerTenant = new UUID(0, 402);
        UUID callerIdentity = new UUID(0, 403);
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(tenant), "trial");
        serve(List.of(tenant));
        var task = new IdentityTask();
        var advised = new AtomicInteger();
        var proxy = new ProxyFactory(task);
        proxy.setInterfaces(TenantOperation.class);
        proxy.setProxyTargetClass(classProxy);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                CurrentTenant.require();
                advised.incrementAndGet();
            }
            return invocation.proceed();
        });
        var scheduler = new ManualScheduler();
        var metrics = new SimpleMeterRegistry();
        var jwt = Jwt.withTokenValue("request-token")
                .header("alg", "RS256")
                .subject(callerIdentity.toString())
                .claim("tenant_id", callerTenant.toString())
                .claim("membership_id", new UUID(0, 404).toString())
                .claim("ver", 1)
                .build();
        var authentication = new JwtAuthenticationToken(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            runner(scheduler, metrics, proxy.getProxy())
                    .withBean(CurrentActorProvider.class, JwtCurrentActorProvider::new)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        task.audits = context.getBean(MetaObjectHandler.class);
                        CurrentTenant.run(callerTenant, () -> {
                            scheduler.fire();
                            assertThat(CurrentTenant.require()).isEqualTo(callerTenant);
                            assertThat(SecurityContextHolder.getContext().getAuthentication())
                                    .isSameAs(authentication);
                        });
                        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                            assertThat(metrics.find("forge.task.duration")
                                            .tag("name", "identity")
                                            .timer())
                                    .isNotNull();
                            assertThat(advised).hasValue(1);
                            assertThat(task.observations)
                                    .containsExactly(
                                            new Observation(tenant, tenant.toString(), null, false, null, null));
                            assertThat(metrics.get("forge.task.tenant.successes")
                                            .tag("name", "identity")
                                            .counter()
                                            .count())
                                    .isEqualTo(1);
                        });
                        assertThat(CurrentTenant.find()).isEmpty();
                    });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    interface TenantOperation {
        void execute();
    }

    record Observation(
            UUID tenant, String logTenant, UUID actor, boolean membershipPresent, UUID createdBy, UUID updatedBy) {}

    static class AuditRow extends AuditableEntity {}

    static class IdentityTask implements TenantOperation {
        MetaObjectHandler audits;
        final List<Observation> observations = Collections.synchronizedList(new ArrayList<>());

        @Override
        @TenantTask(name = "identity", fixedDelay = "1s")
        public void execute() {
            var row = new AuditRow();
            row.setCreatedBy(new UUID(0, 999));
            row.setUpdatedBy(new UUID(0, 999));
            audits.insertFill(SystemMetaObject.forObject(row));
            boolean membershipPresent;
            try {
                TenantContext.current();
                membershipPresent = true;
            } catch (IllegalStateException expected) {
                membershipPresent = false;
            }
            observations.add(new Observation(
                    CurrentTenant.require(),
                    MDC.get(CurrentTenant.MDC_KEY),
                    new JwtCurrentActorProvider().currentActor().orElse(null),
                    membershipPresent,
                    row.getCreatedBy(),
                    row.getUpdatedBy()));
        }
    }

    private ApplicationContextRunner runner(ManualScheduler scheduler, MeterRegistry metrics, Object task) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TenantAutoConfiguration.class, SchedulingAutoConfiguration.class))
                .withBean("forgeTaskScheduler", TaskScheduler.class, () -> scheduler)
                .withBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory(redisConfiguration()))
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(MeterRegistry.class, () -> metrics)
                .withBean("task", Object.class, () -> task)
                .withPropertyValues(
                        "spring.application.name=tenant-execution-test",
                        "spring.data.redis.database=" + redisServer.database(),
                        "forge.scheduling.iam-uri=http://127.0.0.1:"
                                + iam.getAddress().getPort(),
                        "forge.scheduling.token=service-token");
    }

    static String page(List<UUID> tenants, int number, int total) {
        String ids = tenants.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "{\"code\":\"ok\",\"httpStatus\":200,\"data\":[" + ids
                + "],\"meta\":{\"page\":{\"number\":" + number + ",\"size\":100,\"totalElements\":" + total
                + ",\"totalPages\":" + ((total + 99) / 100) + "}}}";
    }

    static class TenantProbe {
        final List<UUID> attempts = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        final UUID failure;

        TenantProbe(UUID failure) {
            this.failure = failure;
        }

        @TenantTask(name = "tenant-flow", fixedDelay = "1s")
        public void execute() {
            UUID tenant = CurrentTenant.require();
            attempts.add(tenant);
            threads.add(Thread.currentThread().getName());
            if (tenant.equals(failure)) {
                throw new IllegalStateException("Expected tenant failure");
            }
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
