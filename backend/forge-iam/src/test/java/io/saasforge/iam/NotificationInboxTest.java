package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.notification.mapper.NotificationIsolationProbe;
import io.saasforge.iam.schema.mapper.SchemaCommentMapper;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.notification.NotificationLink;
import io.saasforge.sdk.notification.NotificationSent;
import io.saasforge.sdk.notification.NotificationTarget;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql(
        scripts = {"/fixtures/notifications.sql", "/fixtures/inbox-readers.sql"},
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
@Import(NotificationInboxTest.Clocks.class)
class NotificationInboxTest {
    static final String KEY_FILE = "target/test-notification-signing.jwk";
    static final UUID TENANT_A = UUID.fromString("019a0110-0000-7000-8000-000000000210");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-000000000220");
    static final UUID ADMIN_A = UUID.fromString("019a0110-0000-7000-8000-000000000212");
    static final UUID MEMBER_A = UUID.fromString("019a0110-0000-7000-8000-000000000214");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-000000000222");
    static final UUID IDENTITY = UUID.fromString("019a0110-0000-7000-8000-000000000211");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
    }

    @LocalServerPort
    int port;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    List<TenantDataCleaner> cleaners;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    MutableClock clock;

    @Autowired
    NotificationIsolationProbe isolation;

    @Autowired
    SchemaCommentMapper schema;

    @BeforeEach
    void resetClock() {
        clock.now = Instant.now();
    }

    @Test
    void deliveredNotificationAppearsOnceOnlyInTheRecipientMembershipInbox() throws Exception {
        NotificationSent event =
                event(NotificationTarget.memberships(List.of(ADMIN_A, ADMIN_A, ADMIN_B)), "订单已创建", Instant.now());
        deliver(event);
        deliver(event);
        ResponseEntity<Map> response = call(TENANT_A, ADMIN_A, "GET", "/notifications?page=1&size=1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(items(response)).hasSize(1);
        assertThat(items(response).get(0))
                .containsEntry("id", event.id().toString())
                .containsEntry("title", "订单已创建")
                .containsEntry("read", false);
        assertThat(object(object(response.getBody().get("meta")).get("page"))).containsEntry("totalElements", 1);
        assertThat(items(call(TENANT_B, ADMIN_B, "GET", "/notifications"))).isEmpty();
        assertThat(items(call(TENANT_A, MEMBER_A, "GET", "/notifications"))).isEmpty();
    }

    @Test
    void rlsProtectsParentsAndDirectMonthlyAccessOnTheApplicationAccount() {
        clock.now = Instant.parse("2026-02-15T12:00:00Z");
        deliver(event(NotificationTarget.memberships(List.of(ADMIN_A)), "甲租户通知", clock.instant()));
        deliver(new NotificationSent(
                UUID.randomUUID(),
                TENANT_B,
                "demo-service",
                NotificationTarget.memberships(List.of(ADMIN_B)),
                "乙租户通知",
                "乙正文",
                null,
                clock.instant()));
        assertThat(isolation.isRestrictedRuntimeRole()).isTrue();
        assertThat(schema.selectUnprotectedNotificationTables()).isEmpty();
        for (boolean directPartition : List.of(false, true)) {
            assertThat(isolation.notificationTenants(directPartition)).isEmpty();
            assertThat(isolation.recipientTenants(directPartition)).isEmpty();
            for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
                transaction.executeWithoutResult(status -> {
                    isolation.bindTenant(tenant);
                    assertThat(isolation.notificationTenants(directPartition)).containsExactly(tenant);
                    assertThat(isolation.recipientTenants(directPartition)).containsExactly(tenant);
                });
            }
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                        isolation.bindTenant(TENANT_A);
                        isolation.insertNotification(UUID.randomUUID(), TENANT_B, clock.instant(), directPartition);
                    }))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("row-level security policy");
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                        isolation.bindTenant(TENANT_A);
                        isolation.insertRecipient(
                                UUID.randomUUID(), TENANT_B, ADMIN_B, clock.instant(), directPartition);
                    }))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("row-level security policy");
            assertThat(isolation.notificationTenants(directPartition)).isEmpty();
            assertThat(isolation.recipientTenants(directPartition)).isEmpty();
        }
    }

    @Test
    void rolledBackDeliveryAndFailedReadDoNotAffectTheNextTenantsInbox() throws Exception {
        NotificationSent rolledBack = event(NotificationTarget.memberships(List.of(ADMIN_A)), "回滚通知", Instant.now());
        transaction.executeWithoutResult(status -> {
            deliver(rolledBack);
            status.setRollbackOnly();
        });
        NotificationSent b = new NotificationSent(
                UUID.randomUUID(),
                TENANT_B,
                "demo-service",
                NotificationTarget.memberships(List.of(ADMIN_B)),
                "乙租户通知",
                "乙正文",
                null,
                Instant.now());
        deliver(b);
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications"))).isEmpty();
        assertThat(call(TENANT_A, ADMIN_A, "POST", "/notifications/" + b.id() + "/read")
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(items(call(TENANT_B, ADMIN_B, "GET", "/notifications")))
                .singleElement()
                .satisfies(item ->
                        assertThat(item).containsEntry("id", b.id().toString()).containsEntry("read", false));
        assertThat(object(call(TENANT_B, ADMIN_B, "GET", "/notifications/unread-count")
                        .getBody()
                        .get("data")))
                .containsEntry("count", 1);
        assertThat(call(TENANT_B, ADMIN_B, "POST", "/notifications/" + b.id() + "/read")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        deliver(rolledBack);
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications")))
                .extracting(item -> item.get("id"))
                .containsExactly(rolledBack.id().toString());
        assertThat(items(call(TENANT_B, ADMIN_B, "GET", "/notifications")))
                .singleElement()
                .satisfies(item -> assertThat(item).containsEntry("read", true));
    }

    @Test
    void concurrentFirstDeliveriesCanShareANewMonthlyPartition() throws Exception {
        clock.now = Instant.parse("2026-03-15T12:00:00Z");
        NotificationSent first = event(NotificationTarget.memberships(List.of(ADMIN_A)), "并发甲", clock.instant());
        NotificationSent second = event(NotificationTarget.memberships(List.of(ADMIN_A)), "并发乙", clock.instant());
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream()
                    .map(event -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        deliver(event);
                        return null;
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications")))
                .extracting(item -> item.get("id"))
                .containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
        assertThat(schema.selectUnprotectedNotificationTables()).isEmpty();
    }

    @Test
    void readAndReadAllAreIdempotentAndCannotTouchAnotherMembership() throws Exception {
        NotificationSent first = event(
                NotificationTarget.memberships(List.of(ADMIN_A)),
                "先创建",
                Instant.now().minusSeconds(5));
        NotificationSent second = event(NotificationTarget.memberships(List.of(ADMIN_A)), "后创建", Instant.now());
        deliver(first);
        deliver(second);
        assertThat(object(call(TENANT_A, ADMIN_A, "GET", "/notifications/unread-count")
                        .getBody()
                        .get("data")))
                .containsEntry("count", 2);
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications?page=2&size=1"))
                        .get(0))
                .containsEntry("id", first.id().toString());
        assertThat(call(TENANT_B, ADMIN_B, "POST", "/notifications/" + first.id() + "/read")
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(TENANT_A, ADMIN_A, "POST", "/notifications/" + first.id() + "/read")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        Object readAt = items(call(TENANT_A, ADMIN_A, "GET", "/notifications?page=2&size=1"))
                .get(0)
                .get("readAt");
        call(TENANT_A, ADMIN_A, "POST", "/notifications/" + first.id() + "/read");
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications?page=2&size=1"))
                        .get(0))
                .containsEntry("readAt", readAt)
                .containsEntry("read", true);
        call(TENANT_B, ADMIN_B, "POST", "/notifications/read-all");
        assertThat(object(call(TENANT_A, ADMIN_A, "GET", "/notifications/unread-count")
                        .getBody()
                        .get("data")))
                .containsEntry("count", 1);
        assertThat(call(TENANT_A, ADMIN_A, "POST", "/notifications/read-all")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(object(call(TENANT_A, ADMIN_A, "GET", "/notifications/unread-count")
                        .getBody()
                        .get("data")))
                .containsEntry("count", 0);
        assertThat(call(TENANT_A, ADMIN_A, "GET", "/notifications?size=101")
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }

    @Test
    void permissionRecipientsAreFrozenAtCreationEvenWhenTheEventIsRedelivered() throws Exception {
        NotificationSent event = event(NotificationTarget.permission("iam:audit:read"), "授权时的通知", Instant.now());
        deliver(event);
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications"))).hasSize(1);
        assertThat(items(call(TENANT_A, MEMBER_A, "GET", "/notifications"))).isEmpty();
        assertThat(items(call(TENANT_B, ADMIN_B, "GET", "/notifications"))).isEmpty();
        assertThat(call(
                                TENANT_A,
                                ADMIN_A,
                                "PUT",
                                "/roles/memberships/" + MEMBER_A,
                                Map.of("roleIds", List.of("019a0110-0000-7000-8000-000000000215")))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        deliver(event);
        assertThat(items(call(TENANT_A, MEMBER_A, "GET", "/notifications"))).isEmpty();
        NotificationSent later = new NotificationSent(
                UUID.randomUUID(),
                TENANT_A,
                "demo-service",
                NotificationTarget.permission("iam:audit:read"),
                "新授权后的通知",
                "查看订单",
                null,
                Instant.now());
        deliver(later);
        assertThat(items(call(TENANT_A, MEMBER_A, "GET", "/notifications"))).hasSize(1);
        assertThat(call(
                                TENANT_A,
                                ADMIN_A,
                                "PUT",
                                "/roles/memberships/" + MEMBER_A,
                                Map.of("roleIds", List.of(readerRoleId())))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(items(call(TENANT_A, MEMBER_A, "GET", "/notifications")))
                .extracting(item -> item.get("id"))
                .containsExactly(later.id().toString());
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications"))).hasSize(2);
    }

    @Test
    void leavingMembershipCannotReadOrChangeItsInboxButOtherTenantStaysAccessible() throws Exception {
        NotificationSent a = event(NotificationTarget.memberships(List.of(MEMBER_A)), "离开前通知", Instant.now());
        deliver(a);
        deliver(new NotificationSent(
                UUID.randomUUID(),
                TENANT_B,
                "demo-service",
                NotificationTarget.memberships(List.of(ADMIN_B)),
                "乙租户通知",
                "乙正文",
                null,
                Instant.now()));
        assertThat(call(TENANT_A, MEMBER_A, "POST", "/members/me/leave")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        for (String uri : List.of("/notifications", "/notifications/unread-count")) {
            assertThat(call(TENANT_A, MEMBER_A, "GET", uri).getStatusCode().value())
                    .isEqualTo(403);
        }
        for (String uri : List.of("/notifications/read-all", "/notifications/" + a.id() + "/read")) {
            assertThat(call(TENANT_A, MEMBER_A, "POST", uri).getStatusCode().value())
                    .isEqualTo(403);
        }
        assertThat(call(TENANT_A, ADMIN_B, "GET", "/notifications")
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(items(call(TENANT_B, ADMIN_B, "GET", "/notifications"))).hasSize(1);
    }

    @Test
    void finalTenantCleanupOnlyRemovesThatTenantsNotifications() throws Exception {
        deliver(event(NotificationTarget.memberships(List.of(ADMIN_A)), "甲租户通知", Instant.now()));
        deliver(new NotificationSent(
                UUID.randomUUID(),
                TENANT_B,
                "demo-service",
                NotificationTarget.memberships(List.of(ADMIN_B)),
                "乙租户通知",
                "乙正文",
                null,
                Instant.now()));
        CurrentTenant.run(
                TENANT_A,
                () -> transaction.executeWithoutResult(
                        status -> cleaners.forEach(cleaner -> cleaner.clean(new TenantDeleted(TENANT_A)))));
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications"))).isEmpty();
        assertThat(items(call(TENANT_B, ADMIN_B, "GET", "/notifications"))).hasSize(1);
        assertThat(object(call(TENANT_B, ADMIN_B, "GET", "/notifications/unread-count")
                        .getBody()
                        .get("data")))
                .containsEntry("count", 1);
    }

    @Test
    void monthlyMaintenanceDropsOnlyWhollyExpiredPartitionsAndOldRedeliveryCannotRecreateThem() throws Exception {
        clock.now = Instant.parse("2026-01-15T12:00:00Z");
        NotificationSent expired = event(NotificationTarget.memberships(List.of(ADMIN_A)), "一月通知", clock.instant());
        NotificationSent boundary =
                event(NotificationTarget.memberships(List.of(ADMIN_A)), "五月通知", Instant.parse("2026-05-01T00:00:00Z"));
        deliver(expired);
        deliver(boundary);
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications"))).hasSize(2);
        clock.now = Instant.parse("2026-11-07T12:00:00Z");
        triggerMaintenance();
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications")))
                        .extracting(item -> item.get("id"))
                        .containsExactly(boundary.id().toString()));
        deliver(expired);
        assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications"))).hasSize(1);
        clock.now = Instant.parse("2026-12-01T12:00:00Z");
        triggerMaintenance();
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(items(call(TENANT_A, ADMIN_A, "GET", "/notifications")))
                        .isEmpty());
    }

    void triggerMaintenance() {
        var response = RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri("/internal/ops/tasks/notification-partitions/trigger")
                .headers(h -> h.setBearerAuth("test-forge-iam-ops-token"))
                .body(Map.of("operatorId", UUID.randomUUID().toString()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Object runId = object(response.getBody().get("data")).get("runId");
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var runs = RestClient.create("http://127.0.0.1:" + port)
                    .get()
                    .uri("/internal/ops/tasks/notification-partitions/runs")
                    .headers(h -> h.setBearerAuth("test-forge-iam-ops-token"))
                    .retrieve()
                    .toEntity(Map.class);
            assertThat(items(runs))
                    .anySatisfy(
                            run -> assertThat(run).containsEntry("id", runId).containsEntry("status", "SUCCEEDED"));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        MutableClock notificationClock() {
            return new MutableClock();
        }

        @Bean(name = "forgeTaskScheduler")
        ThreadPoolTaskScheduler scheduler() {
            return new ThreadPoolTaskScheduler() {
                @Override
                public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
                    return null;
                }
            };
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void suspendedMembershipKeepsItsRecipientSnapshotAndCanReadAfterResuming() throws Exception {
        assertThat(call(TENANT_A, ADMIN_A, "POST", "/members/" + MEMBER_A + "/suspend")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        NotificationSent event = event(NotificationTarget.memberships(List.of(MEMBER_A)), "停用期间通知", Instant.now());
        deliver(event);
        assertThat(call(TENANT_A, MEMBER_A, "GET", "/notifications")
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(TENANT_A, ADMIN_A, "POST", "/members/" + MEMBER_A + "/resume")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(items(call(TENANT_A, MEMBER_A, "GET", "/notifications")))
                .extracting(item -> item.get("id"))
                .containsExactly(event.id().toString());
    }

    private String readerRoleId() throws Exception {
        return items(call(TENANT_A, ADMIN_A, "GET", "/roles")).stream()
                .filter(role -> "inbox-reader".equals(role.get("code")))
                .map(role -> (String) role.get("id"))
                .findFirst()
                .orElseThrow();
    }

    NotificationSent event(NotificationTarget target, String title, Instant occurredAt) {
        return new NotificationSent(
                UUID.randomUUID(),
                TENANT_A,
                "demo-service",
                target,
                title,
                "查看订单",
                new NotificationLink("demo", "/overview"),
                occurredAt);
    }

    @SuppressWarnings("unchecked")
    void deliver(NotificationSent event) {
        EventHandler<NotificationSent> handler = (EventHandler<NotificationSent>) handlers.stream()
                .filter(h -> h.eventType().equals(NotificationSent.class))
                .findFirst()
                .orElseThrow();
        assertThat(handler.topic()).isEqualTo(NotificationSent.TOPIC);
        handler.handle(event);
    }

    ResponseEntity<Map> call(UUID tenant, UUID membership, String method, String uri) throws Exception {
        return call(tenant, membership, method, uri, null);
    }

    ResponseEntity<Map> call(UUID tenant, UUID membership, String method, String uri, Object body) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject((membership.equals(MEMBER_A)
                                ? UUID.fromString("019a0110-0000-7000-8000-000000000213")
                                : IDENTITY)
                        .toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenant.toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(HttpMethod.valueOf(method))
                .uri(uri)
                .headers(h -> h.setBearerAuth(jwt.serialize()));
        if (body != null) {
            request.body(body);
        }
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(ResponseEntity<Map> response) {
        assertThat(response.getBody().get("code")).isEqualTo("ok");
        return (List<Map<String, Object>>) response.getBody().get("data");
    }
}
