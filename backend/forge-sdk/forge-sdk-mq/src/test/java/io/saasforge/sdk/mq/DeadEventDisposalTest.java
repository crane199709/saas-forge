package io.saasforge.sdk.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.sdk.core.CurrentTenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = DeadEventDisposalTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "forge.mq.enabled=false",
            "forge.ops.token=dead-event-test",
            "forge.security.issuer=http://tenant.test",
            "forge.security.jwk-set-uri=http://localhost/jwks",
            "spring.application.name=dead-event-test",
            "spring.flyway.enabled=false"
        })
class DeadEventDisposalTest {
    static final SharedContainers.Database DATABASE = SharedContainers.database();

    static {
        var dataSource =
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        new ResourceDatabasePopulator(
                        new ClassPathResource("io/saasforge/sdk/mq/schema.sql"),
                        new ClassPathResource("disposal-fixture.sql"))
                .execute(dataSource);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    SqlSessionTemplate session;

    @Test
    void operatorReadsMetadataWithoutPayloadWhileMqIsDisabled() {
        UUID id = UUID.randomUUID();
        MqAutoConfiguration.mapper(session, DeadEventMapper.class)
                .insertIfAbsent(
                        id,
                        "disposal",
                        "read",
                        UUID.randomUUID(),
                        null,
                        "orders",
                        "Created",
                        "SECRET_PAYLOAD".getBytes(),
                        16,
                        "handler failed");
        var reply = get("/internal/ops/dead-events?status=PENDING&page=1&size=20");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body())
                .contains(id.toString(), "handler failed", "PENDING")
                .doesNotContain("payload", "SECRET_PAYLOAD", "originalTenantId");
    }

    @Test
    void replayRestoresTheOriginalTenantAndCommitsTheHandlerWithItsTerminalState() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID id = seed(target, "replay", "Created", "{\"value\":\"replayed\"}");
        Reply reply = post(id, "replay", "修复后重放");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("REPLAYED", "修复后重放").doesNotContain("payload");
        assertThat(values(target)).containsExactly("replayed");
        assertThat(values(other)).isEmpty();
        assertThat(post(id, "replay", "再次重放").status()).isEqualTo(409);
        assertThat(values(target)).containsExactly("replayed");
        assertThat(CurrentTenant.find()).isEmpty();
    }

    @Test
    void failedReplayRollsBackBusinessAndRetainsPendingWithTheNewError() {
        UUID tenant = UUID.randomUUID();
        UUID id = seed(tenant, "failure", "Created", "{\"value\":\"failing\"}");
        assertThat(post(id, "replay", "验证修复").status()).isEqualTo(409);
        Reply detail = get("/internal/ops/dead-events/" + id);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body())
                .contains("PENDING", "new handler failure", "\"resolvedAt\":null", "\"resolutionReason\":null")
                .doesNotContain("payload");
        assertThat(values(tenant)).isEmpty();
    }

    @Test
    void abandonRequiresAReasonAndMakesFurtherDisposalAConflict() {
        UUID tenant = UUID.randomUUID();
        UUID id = seed(tenant, "abandon", "Created", "{\"value\":\"discarded\"}");
        assertThat(post(id, "abandon", "   ").status()).isEqualTo(400);
        assertThat(post(id, "replay", "   ").status()).isEqualTo(400);
        Reply reply = post(id, "abandon", "确认无需处理");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("ABANDONED", "确认无需处理").doesNotContain("payload");
        assertThat(post(id, "replay", "重放").status()).isEqualTo(409);
        assertThat(post(id, "abandon", "再放弃").status()).isEqualTo(409);
        assertThat(values(tenant)).isEmpty();
    }

    @Autowired
    ReplayBarrier barrier;

    @Autowired
    DeadEventOperations operations;

    @Test
    void concurrentReplaysAndAbandonOnlyCommitOneDisposal() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID id = seed(tenant, "concurrent", "Created", "{\"value\":\"blocked\"}");
        var replay = CompletableFuture.supplyAsync(() -> post(id, "replay", "并发重放"));
        assertThat(barrier.started.await(5, TimeUnit.SECONDS)).isTrue();
        var abandon = CompletableFuture.supplyAsync(() -> post(id, "abandon", "并发放弃"));
        var otherReplay = CompletableFuture.supplyAsync(() -> post(id, "replay", "再次重放"));
        barrier.release.countDown();
        assertThat(replay.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(abandon.get(5, TimeUnit.SECONDS).status()).isEqualTo(409);
        assertThat(otherReplay.get(5, TimeUnit.SECONDS).status()).isEqualTo(409);
        assertThat(values(tenant)).containsExactly("blocked");
    }

    @Test
    void removedHandlerAndMalformedPayloadRemainPending() {
        for (String tag : List.of("Removed", "Created")) {
            UUID id = seed(UUID.randomUUID(), "invalid-" + tag, tag, "not JSON");
            assertThat(post(id, "replay", "验证修复").status()).isEqualTo(409);
            assertThat(get("/internal/ops/dead-events/" + id).body()).contains("PENDING");
        }
        UUID id = UUID.randomUUID();
        MqAutoConfiguration.mapper(session, DeadEventMapper.class)
                .insertIfAbsent(
                        id,
                        "disposal",
                        "malformed-header",
                        null,
                        "malformed",
                        "orders",
                        "Created",
                        "{}".getBytes(),
                        16,
                        "previous");
        assertThat(post(id, "replay", "验证租户").status()).isEqualTo(409);
        assertThat(get("/internal/ops/dead-events/" + id).body()).contains("PENDING", "Invalid UUID");
    }

    @Test
    void retentionDeletesOnlyEventsResolvedMoreThanNinetyDaysAgo() {
        UUID tenant = UUID.randomUUID();
        UUID pending = seed(tenant, "old-pending", "Created", "{}");
        UUID oldAbandoned = seed(tenant, "old-abandoned", "Created", "{}");
        UUID oldReplayed = seed(tenant, "old-replayed", "Created", "{}");
        UUID recent = seed(tenant, "recent-resolution", "Created", "{}");
        BusinessProbe probe = MqAutoConfiguration.mapper(session, BusinessProbe.class);
        probe.age(pending, "PENDING", null);
        probe.age(oldAbandoned, "ABANDONED", Instant.now().minus(91, ChronoUnit.DAYS));
        probe.age(oldReplayed, "REPLAYED", Instant.now().minus(91, ChronoUnit.DAYS));
        probe.age(recent, "ABANDONED", Instant.now().minus(89, ChronoUnit.DAYS));
        operations.purgeResolved();
        assertThat(get("/internal/ops/dead-events/" + pending).status()).isEqualTo(200);
        assertThat(get("/internal/ops/dead-events/" + recent).status()).isEqualTo(200);
        assertThat(get("/internal/ops/dead-events/" + oldAbandoned).status()).isEqualTo(404);
        assertThat(get("/internal/ops/dead-events/" + oldReplayed).status()).isEqualTo(404);
    }

    UUID seed(UUID tenant, String eventId, String tag, String payload) {
        UUID id = UUID.randomUUID();
        MqAutoConfiguration.mapper(session, DeadEventMapper.class)
                .insertIfAbsent(
                        id,
                        "disposal",
                        eventId,
                        tenant,
                        tenant == null ? null : tenant.toString(),
                        "orders",
                        tag,
                        payload.getBytes(),
                        16,
                        "original failure");
        return id;
    }

    List<String> values(UUID tenant) {
        var result = new AtomicReference<List<String>>();
        CurrentTenant.run(
                tenant,
                () -> result.set(
                        MqAutoConfiguration.mapper(session, BusinessProbe.class).values()));
        return result.get();
    }

    Reply post(UUID id, String action, String reason) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri("/internal/ops/dead-events/" + id + "/" + action)
                .header("Authorization", "Bearer dead-event-test")
                .body(Map.of("reason", reason))
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    Reply get(String path) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Authorization", "Bearer dead-event-test")
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    record Reply(int status, String body) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
        @Bean
        ReplayBarrier replayBarrier() {
            return new ReplayBarrier();
        }

        @Bean
        EventHandler<Created> replayHandler(SqlSessionTemplate session, ReplayBarrier barrier) {
            return new EventHandler<>() {
                public String topic() {
                    return "orders";
                }

                public Class<Created> eventType() {
                    return Created.class;
                }

                public void handle(Created event) {
                    MqAutoConfiguration.mapper(session, BusinessProbe.class)
                            .write(CurrentTenant.require(), event.value());
                    if (event.value().equals("blocked")) {
                        barrier.started.countDown();
                        try {
                            if (!barrier.release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("barrier timed out");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    }
                    if (event.value().startsWith("fail")) throw new IllegalStateException("new handler failure");
                }
            };
        }
    }

    static class ReplayBarrier {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    record Created(String value) {}

    @InterceptorIgnore(tenantLine = "true")
    interface BusinessProbe {
        void write(@Param("tenant") UUID tenant, @Param("value") String value);

        List<String> values();

        void age(@Param("id") UUID id, @Param("status") String status, @Param("resolved") Instant resolved);
    }
}
