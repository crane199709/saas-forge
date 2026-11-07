package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = TaskOpsHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.application.name=task-ops-http-test",
            "forge.business-zone=Asia/Shanghai",
            "forge.ops.token=task-ops-token",
            "forge.security.issuer=http://tenant.test",
            "forge.security.jwk-set-uri=http://localhost/jwks"
        })
class TaskOpsHttpTest {
    static final SharedContainers.Redis REDIS = SharedContainers.redis();
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final UUID TARGET = UUID.randomUUID();
    static final UUID OTHER = UUID.randomUUID();
    static final HttpServer IAM;

    static {
        try {
            IAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IAM.createContext("/internal/tenants", exchange -> {
                byte[] body = JSON.writeValueAsBytes(Map.of(
                        "code",
                        "ok",
                        "httpStatus",
                        200,
                        "data",
                        List.of(TARGET, OTHER),
                        "meta",
                        Map.of("page", Map.of("number", 1, "size", 100, "totalElements", 2, "totalPages", 1))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            IAM.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @AfterAll
    static void stop() {
        IAM.stop(0);
    }

    @Autowired
    TenantProbe tenantTask;

    @Autowired
    StringRedisTemplate redis;

    @LocalServerPort
    int port;

    @Autowired
    GlobalProbe task;

    @Autowired
    ManualScheduler scheduler;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "forge.scheduling.iam-uri",
                () -> "http://127.0.0.1:" + IAM.getAddress().getPort());
        registry.add("forge.scheduling.token", () -> "iam-token");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", REDIS::database);
    }

    @BeforeEach
    void clearHistory() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .until(() -> redis.keys("forge:task:task-ops-http-test:*").isEmpty());
        redis.delete(redis.keys("forge:task-run:task-ops-http-test:*"));
        task.calls.set(0);
        task.release = new CountDownLatch(1);
        tenantTask.fail = false;
        tenantTask.tenants.clear();
        tenantTask.authentications.clear();
    }

    @Test
    void manualTriggerReturnsItsRunImmediatelyAndSharesTheScheduledLock() {
        UUID operator = UUID.randomUUID();
        Reply trigger = trigger("global", Map.of("operatorId", operator));
        assertThat(trigger.status()).isEqualTo(200);
        String runId = JSON.readTree(trigger.body()).path("data").path("runId").asString();
        assertThat(runId).isNotBlank();
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(task.calls).hasValue(1));
            var list = JSON.readTree(read("/internal/ops/tasks").body()).path("data");
            assertThat(list.size()).isEqualTo(2);
            var global = StreamSupport.stream(list.spliterator(), false)
                    .filter(declaration -> declaration.path("name").asString().equals("global"))
                    .findFirst()
                    .orElseThrow();
            assertThat(global.path("fixedDelay").asString()).isEqualTo("PT1H");
            var run = global.path("latestRun");
            assertThat(run.path("id").asString()).isEqualTo(runId);
            assertThat(run.path("trigger").asString()).isEqualTo("MANUAL");
            assertThat(run.path("operatorId").asString()).isEqualTo(operator.toString());
            Reply conflict = trigger("global", Map.of("operatorId", operator));
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.body()).contains("task.alreadyRunning");
            scheduler.fire();
            assertThat(task.calls).hasValue(1);
        } finally {
            task.release.countDown();
        }
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var runs = JSON.readTree(read("/internal/ops/tasks/global/runs").body())
                    .path("data");
            assertThat(runs.size()).isEqualTo(1);
            assertThat(runs.get(0).path("id").asString()).isEqualTo(runId);
            assertThat(runs.get(0).path("status").asString()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void aScheduledRunAlsoRejectsManualTriggersWhileItHoldsTheLock() {
        scheduler.fire();
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(task.calls).hasValue(1));
            Reply conflict = trigger("global", Map.of("operatorId", UUID.randomUUID()));
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.body()).contains("task.alreadyRunning");
            assertThat(task.calls).hasValue(1);
        } finally {
            task.release.countDown();
        }
    }

    @Test
    void singleTenantRetryBindsOnlyTheTargetAndDoesNotInheritARequestIdentity() {
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(TARGET), "active");
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(OTHER), "trial");
        UUID operator = UUID.randomUUID();
        Reply reply = trigger("tenant", Map.of("operatorId", operator, "targetTenantId", TARGET));
        assertThat(reply.status()).isEqualTo(200);
        String id = JSON.readTree(reply.body()).path("data").path("runId").asString();
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var runs = JSON.readTree(read("/internal/ops/tasks/tenant/runs").body())
                    .path("data");
            assertThat(runs.size()).isEqualTo(1);
            assertThat(runs.get(0).path("id").asString()).isEqualTo(id);
            assertThat(runs.get(0).path("targetTenantId").asString()).isEqualTo(TARGET.toString());
            assertThat(runs.get(0).path("successCount").asInt()).isEqualTo(1);
        });
        assertThat(tenantTask.tenants).containsExactly(TARGET);
        assertThat(tenantTask.authentications).containsExactly(false);
    }

    @Test
    void rejectsIneligibleTenantsAndGlobalTenantParametersBeforeAcceptingWork() {
        UUID inactive = UUID.randomUUID();
        for (String status : List.of("pending", "suspended", "expired", "archived", "deleted", "")) {
            redis.opsForValue().set(MembershipStateKeys.tenantStatus(inactive), status);
            Reply rejected = trigger("tenant", Map.of("operatorId", UUID.randomUUID(), "targetTenantId", inactive));
            assertThat(rejected.status()).isEqualTo(409);
            assertThat(rejected.body()).contains("task.tenantNotEligible");
        }
        Reply global = trigger("global", Map.of("operatorId", UUID.randomUUID(), "targetTenantId", inactive));
        assertThat(global.status()).isEqualTo(400);
        assertThat(global.body()).contains("request.invalid");
    }

    @Test
    void problemHistoryRetainsTheFailedTenantAfterASubsequentSuccess() {
        var declarations = JSON.readTree(read("/internal/ops/tasks").body()).path("data");
        for (var declaration : declarations) {
            assertThat(declaration.path("latestRun").isNull()).isTrue();
            if (declaration.path("type").asString().equals("TENANT")) {
                assertThat(declaration.path("cron").asString()).isEqualTo("0 0 9 * * *");
                assertThat(declaration.path("zone").asString()).isEqualTo("Asia/Shanghai");
                assertThat(declaration.path("fixedDelay").isNull()).isTrue();
            }
        }
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(TARGET), "active");
        tenantTask.fail = true;
        Reply failed = trigger("tenant", Map.of("operatorId", UUID.randomUUID(), "targetTenantId", TARGET));
        assertThat(failed.status()).isEqualTo(200);
        String failedId =
                JSON.readTree(failed.body()).path("data").path("runId").asString();
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var runs = JSON.readTree(read("/internal/ops/tasks/tenant/runs").body())
                    .path("data");
            assertThat(runs.size()).isEqualTo(1);
            assertThat(runs.get(0).path("status").asString()).isEqualTo("FAILED");
        });
        tenantTask.fail = false;
        assertThat(trigger("tenant", Map.of("operatorId", UUID.randomUUID(), "targetTenantId", TARGET))
                        .status())
                .isEqualTo(200);
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var runs = JSON.readTree(read("/internal/ops/tasks/tenant/runs").body())
                    .path("data");
            assertThat(runs.size()).isEqualTo(2);
            assertThat(runs.get(0).path("status").asString()).isEqualTo("SUCCEEDED");
        });
        Reply problems = read("/internal/ops/tasks/tenant/runs?view=problems");
        var runs = JSON.readTree(problems.body()).path("data");
        assertThat(runs.size()).isEqualTo(1);
        assertThat(runs.get(0).path("id").asString()).isEqualTo(failedId);
        assertThat(runs.get(0).path("failureCount").asInt()).isEqualTo(1);
        assertThat(runs.get(0).path("failedTenants").get(0).path("tenantId").asString())
                .isEqualTo(TARGET.toString());
        assertThat(problems.body()).contains("IllegalStateException").doesNotContain("sensitive-order-42");
    }

    @Test
    void rejectsUnknownNamesViewsAndMissingOperatorsAndRequiresTheOpsToken() {
        assertThat(read("/internal/ops/tasks/missing/runs").status()).isEqualTo(404);
        assertThat(trigger("missing", Map.of("operatorId", UUID.randomUUID())).status())
                .isEqualTo(404);
        assertThat(read("/internal/ops/tasks/global/runs?view=unknown").status())
                .isEqualTo(400);
        assertThat(trigger("global", Map.of()).status()).isEqualTo(400);
        int unauthorized = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/internal/ops/tasks")
                .header("Authorization", "Bearer wrong-token")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(unauthorized).isEqualTo(401);
    }

    Reply trigger(String name, Object body) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/internal/ops/tasks/" + name + "/trigger")
                .header("Authorization", "Bearer task-ops-token")
                .body(body)
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    Reply read(String path) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Authorization", "Bearer task-ops-token")
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    record Reply(int status, String body) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    static class Application {
        @Bean
        TenantProbe tenantProbe() {
            return new TenantProbe();
        }

        @Bean
        GlobalProbe probe() {
            return new GlobalProbe();
        }

        @Bean("forgeTaskScheduler")
        ManualScheduler scheduler() {
            return new ManualScheduler();
        }
    }

    static class GlobalProbe {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch release = new CountDownLatch(1);

        @GlobalTask(name = "global", fixedDelay = "1h")
        void execute() throws InterruptedException {
            calls.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
        }
    }

    static class TenantProbe {
        volatile boolean fail;
        final List<UUID> tenants = new CopyOnWriteArrayList<>();
        final List<Boolean> authentications = new CopyOnWriteArrayList<>();

        @TenantTask(name = "tenant", cron = "0 0 9 * * *")
        void execute() {
            tenants.add(CurrentTenant.require());
            if (fail) throw new IllegalStateException("sensitive-order-42");
            authentications.add(SecurityContextHolder.getContext().getAuthentication() != null);
        }
    }

    static class ManualScheduler extends ThreadPoolTaskScheduler {
        Runnable trigger;

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            if (delay.equals(Duration.ofHours(1))) trigger = task;
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return null;
        }

        void fire() {
            trigger.run();
        }
    }
}
