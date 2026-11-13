package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.core.SubjectAttributes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 通过公开 HTTP 边界验证 Order 恢复；每个用例使用独立 Tenant。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderRestoreTest {
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
    StringRedisTemplate redisTemplate;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    JsonMapper json;

    UUID tenant;
    String token;

    @BeforeEach
    void grants() throws Exception {
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        token = TestTokens.forTenant(tenant);
    }

    @Test
    void deletedViewContainsOnlyDeletedOrdersAndShowsDeletionTimeAndActor() {
        Map<?, ?> deleted = create(token, "误删订单", 12);
        Map<?, ?> secondDeleted = create(token, "另一误删订单", 56);
        Map<?, ?> active = create(token, "保留订单", 34);
        assertThat(exchange(token, HttpMethod.DELETE, "/orders/" + deleted.get("id"), null)
                        .get("httpStatus"))
                .isEqualTo(200);

        assertThat(exchange(token, HttpMethod.DELETE, "/orders/" + secondDeleted.get("id"), null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders", null))).containsExactly(active.get("id"));
        Map<String, Object> page =
                exchange(token, HttpMethod.GET, "/orders?deleted=true&size=1&sort=amount,desc", null);
        assertThat(ids(page)).containsExactly(secondDeleted.get("id"));
        assertThat(page.get("meta"))
                .isEqualTo(Map.of("page", Map.of("number", 1, "size", 1, "totalElements", 2, "totalPages", 2)));
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders?deleted=true&page=2&size=1&sort=amount,desc", null)))
                .containsExactly(deleted.get("id"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) page.get("data")).get(0);
        assertThat(OffsetDateTime.parse((String) row.get("updatedAt")))
                .isAfterOrEqualTo(OffsetDateTime.parse((String) secondDeleted.get("createdAt")));
        assertThat(row.get("updatedBy")).isEqualTo(TestTokens.IDENTITY_ID.toString());
    }

    @Test
    void restorePreservesBusinessNumberAndStatusAndOnlySucceedsOnce() {
        Map<?, ?> original = create(token, "已关闭误删订单", 56);
        String id = (String) original.get("id");
        assertThat(exchange(token, HttpMethod.POST, "/orders/" + id + "/close", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        exchange(token, HttpMethod.DELETE, "/orders/" + id, null);
        Map<?, ?> deleted = (Map<?, ?>) ((List<?>) exchange(token, HttpMethod.GET, "/orders?deleted=true", null)
                        .get("data"))
                .get(0);

        Map<String, Object> restored = exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null);
        assertThat(restored.get("httpStatus")).isEqualTo(200);
        assertThat(restored.get("code")).isEqualTo("ok");
        assertThat(restored).doesNotContainKey("data");
        Map<?, ?> visible = (Map<?, ?>)
                exchange(token, HttpMethod.GET, "/orders/" + id, null).get("data");
        assertThat(visible.get("number")).isEqualTo(original.get("number"));
        assertThat(visible.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(visible.get("title")).isEqualTo("已关闭误删订单");
        assertThat(visible.get("amount")).isEqualTo(original.get("amount"));
        assertThat(visible.get("status")).isEqualTo("closed");
        assertThat(visible.get("ownerId")).isEqualTo(original.get("ownerId"));
        assertThat(OffsetDateTime.parse((String) visible.get("updatedAt")))
                .isAfter(OffsetDateTime.parse((String) deleted.get("updatedAt")));
        assertThat(visible.get("updatedBy")).isEqualTo(TestTokens.IDENTITY_ID.toString());
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders?deleted=true", null)))
                .isEmpty();
        assertThat(exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null)
                        .get("code"))
                .isEqualTo("order.notFound");
        assertThat(exchange(token, HttpMethod.POST, "/orders/" + UUID.randomUUID() + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void restoreAuditsSuccessAndFailureWithoutReopeningTodosOrSendingNotifications() {
        Map<?, ?> order = create(token, "保持未支付状态", 78);
        String id = (String) order.get("id");
        exchange(token, HttpMethod.DELETE, "/orders/" + id, null);
        List<String> openedBefore = outbox.payloads(tenant, "TodoOpened");
        List<String> sentBefore = outbox.payloads(tenant, "NotificationSent");
        List<String> closedBefore = outbox.payloads(tenant, "TodoClosed");
        assertThat(openedBefore).hasSize(1);
        assertThat(sentBefore).hasSize(1);
        assertThat(closedBefore).hasSize(1);

        assertThat(exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(((Map<?, ?>) exchange(token, HttpMethod.GET, "/orders/" + id, null)
                                .get("data"))
                        .get("status"))
                .isEqualTo("unpaid");
        exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null);
        List<AuditEvent> events = outbox.payloads(tenant, "AuditEvent").stream()
                .map(payload -> json.readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("order.restore"))
                .toList();
        assertThat(events).extracting(AuditEvent::outcome).containsExactly(AuditOutcome.SUCCESS, AuditOutcome.FAILURE);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
            assertThat(event.resourceType()).isEqualTo("order");
            assertThat(event.resourceId()).isEqualTo(id);
            assertThat(event.traceId()).hasSize(32);
        });
        assertThat(events.get(1).errorCode()).isEqualTo("order.notFound");
        assertThat(outbox.payloads(tenant, "TodoOpened")).isEqualTo(openedBefore);
        assertThat(outbox.payloads(tenant, "NotificationSent")).isEqualTo(sentBefore);
        assertThat(outbox.payloads(tenant, "TodoClosed")).isEqualTo(closedBefore);
    }

    @Test
    @Sql("/fixtures/order-restore-conflict.sql")
    void businessNumberConflictReturnsSharedErrorAndCanBeResolvedBeforeRestore() throws Exception {
        UUID fixtureTenant = UUID.fromString("019a0110-0000-7000-8000-0000000000a3");
        TestGrants.publishAll(redisTemplate, fixtureTenant, TestTokens.MEMBERSHIP_ID);
        String caller = TestTokens.forTenant(fixtureTenant);
        String deletedId = "019a0110-0000-7000-8000-0000000000a4";
        String activeId = "019a0110-0000-7000-8000-0000000000a5";

        Map<String, Object> conflict = exchange(caller, HttpMethod.POST, "/orders/" + deletedId + "/restore", null);
        assertThat(conflict.get("httpStatus")).isEqualTo(409);
        assertThat(conflict.get("code")).isEqualTo("resource.restoreConflict");
        assertThat(conflict.get("userTip")).isEqualTo("名称或编码已被占用，请先改名或处理冲突记录后再恢复");
        assertThat(ids(exchange(caller, HttpMethod.GET, "/orders", null))).containsExactly(activeId);
        assertThat(ids(exchange(caller, HttpMethod.GET, "/orders?deleted=true", null)))
                .containsExactly(deletedId);

        exchange(caller, HttpMethod.DELETE, "/orders/" + activeId, null);
        assertThat(exchange(caller, HttpMethod.POST, "/orders/" + deletedId + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(((Map<?, ?>) exchange(caller, HttpMethod.GET, "/orders/" + deletedId, null)
                                .get("data"))
                        .get("number"))
                .isEqualTo("SO-20261109-0042");
    }

    @Test
    void deletedViewAndRestoreRequireDeletePermissionWhileNormalViewKeepsReadPermission() throws Exception {
        Map<?, ?> order = create(token, "权限验证订单", 12);
        String id = (String) order.get("id");
        exchange(token, HttpMethod.DELETE, "/orders/" + id, null);
        UUID reader = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, reader, List.of("order:read"));
        String readOnly = TestTokens.forMembership(tenant, reader);
        assertThat(exchange(readOnly, HttpMethod.GET, "/orders?deleted=false", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(readOnly, HttpMethod.GET, "/orders?deleted=true", null)
                        .get("code"))
                .isEqualTo("auth.forbidden");
        assertThat(exchange(readOnly, HttpMethod.POST, "/orders/" + id + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(403);
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders?deleted=true", null)))
                .containsExactly(id);

        UUID manager = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, manager, List.of("order:delete"));
        String deleteOnly = TestTokens.forMembership(tenant, manager);
        for (String path : List.of("/orders", "/orders?deleted=false")) {
            assertThat(exchange(deleteOnly, HttpMethod.GET, path, null).get("httpStatus"))
                    .isEqualTo(403);
        }
        assertThat(ids(exchange(deleteOnly, HttpMethod.GET, "/orders?deleted=true", null)))
                .containsExactly(id);
        assertThat(exchange(deleteOnly, HttpMethod.POST, "/orders/" + id + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(200);
    }

    @Test
    void dataPolicyFiltersDeletedPagesCountsAndRestoresAndDenyByDefaultStillApplies() throws Exception {
        Map<?, ?> mine = create(token, "本人误删订单", 12);
        UUID otherMember = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, otherMember);
        String other = TestTokens.forMembership(tenant, otherMember);
        Map<?, ?> theirs = create(other, "他人误删订单", 34);
        exchange(token, HttpMethod.DELETE, "/orders/" + mine.get("id"), null);
        exchange(token, HttpMethod.DELETE, "/orders/" + theirs.get("id"), null);
        TestGrants.publish(
                redisTemplate,
                tenant,
                TestTokens.MEMBERSHIP_ID,
                2,
                TestGrants.ALL_ORDER_PERMISSIONS,
                SubjectAttributes.NONE,
                List.of(DataScope.SELF));
        String self = TestTokens.forMembership(tenant, TestTokens.MEMBERSHIP_ID, 2);

        Map<String, Object> page = exchange(self, HttpMethod.GET, "/orders?deleted=true", null);
        assertThat(ids(page)).containsExactly(mine.get("id"));
        assertThat(page.get("meta"))
                .isEqualTo(Map.of("page", Map.of("number", 1, "size", 20, "totalElements", 1, "totalPages", 1)));
        assertThat(exchange(self, HttpMethod.POST, "/orders/" + theirs.get("id") + "/restore", null)
                        .get("code"))
                .isEqualTo("order.notFound");
        TestGrants.publish(
                redisTemplate,
                tenant,
                TestTokens.MEMBERSHIP_ID,
                3,
                TestGrants.ALL_ORDER_PERMISSIONS,
                SubjectAttributes.NONE,
                List.of());
        String denied = TestTokens.forMembership(tenant, TestTokens.MEMBERSHIP_ID, 3);
        assertThat(ids(exchange(denied, HttpMethod.GET, "/orders?deleted=true", null)))
                .isEmpty();
        assertThat(exchange(denied, HttpMethod.POST, "/orders/" + mine.get("id") + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(404);

        assertThat(exchange(self, HttpMethod.POST, "/orders/" + mine.get("id") + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(ids(exchange(self, HttpMethod.GET, "/orders", null))).containsExactly(mine.get("id"));
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders?deleted=true", null)))
                .containsExactly(theirs.get("id"));
    }

    @Test
    void deletedOrdersCannotBeReadOrRestoredAcrossTenants() throws Exception {
        Map<?, ?> mine = create(token, "本租户订单", 12);
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, otherTenant, TestTokens.MEMBERSHIP_ID);
        String other = TestTokens.forTenant(otherTenant);
        Map<?, ?> theirs = create(other, "其他租户订单", 34);
        exchange(token, HttpMethod.DELETE, "/orders/" + mine.get("id"), null);
        exchange(other, HttpMethod.DELETE, "/orders/" + theirs.get("id"), null);

        assertThat(ids(exchange(token, HttpMethod.GET, "/orders?deleted=true", null)))
                .containsExactly(mine.get("id"));
        assertThat(exchange(token, HttpMethod.POST, "/orders/" + theirs.get("id") + "/restore", null)
                        .get("code"))
                .isEqualTo("order.notFound");
        assertThat(exchange(token, HttpMethod.GET, "/orders/" + theirs.get("id"), null)
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(ids(exchange(other, HttpMethod.GET, "/orders?deleted=true", null)))
                .containsExactly(theirs.get("id"));
        assertThat(exchange(other, HttpMethod.POST, "/orders/" + mine.get("id") + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchange(token, HttpMethod.POST, "/orders/" + mine.get("id") + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(other, HttpMethod.POST, "/orders/" + theirs.get("id") + "/restore", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders", null))).containsExactly(mine.get("id"));
        assertThat(ids(exchange(other, HttpMethod.GET, "/orders", null))).containsExactly(theirs.get("id"));
    }

    @Test
    void concurrentRestoreOnlySucceedsOnceAndKeepsTheOriginalOrder() throws Exception {
        Map<?, ?> order = create(token, "并发恢复订单", 12);
        String id = (String) order.get("id");
        exchange(token, HttpMethod.DELETE, "/orders/" + id, null);
        List<Map<String, Object>> results = race(
                () -> exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null),
                () -> exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null));
        assertThat(results).extracting(result -> result.get("httpStatus")).containsExactlyInAnyOrder(200, 404);
        assertThat(results).extracting(result -> result.get("code")).containsExactlyInAnyOrder("ok", "order.notFound");
        Map<?, ?> visible = (Map<?, ?>)
                exchange(token, HttpMethod.GET, "/orders/" + id, null).get("data");
        assertThat(visible.get("number")).isEqualTo(order.get("number"));
        assertThat(visible.get("status")).isEqualTo("unpaid");
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders", null))).containsExactly(id);
        assertThat(ids(exchange(token, HttpMethod.GET, "/orders?deleted=true", null)))
                .isEmpty();
    }

    @Test
    void restoreRacingWithDeleteHasAConsistentPublicResult() throws Exception {
        Map<?, ?> order = create(token, "恢复与删除竞争订单", 34);
        String id = (String) order.get("id");
        exchange(token, HttpMethod.DELETE, "/orders/" + id, null);
        List<Map<String, Object>> results = race(
                () -> exchange(token, HttpMethod.POST, "/orders/" + id + "/restore", null),
                () -> exchange(token, HttpMethod.DELETE, "/orders/" + id, null));
        assertThat(results.get(0).get("httpStatus")).isEqualTo(200);
        int deleteStatus = (Integer) results.get(1).get("httpStatus");
        assertThat(deleteStatus).isIn(200, 404);
        List<Object> active = ids(exchange(token, HttpMethod.GET, "/orders", null));
        List<Object> deleted = ids(exchange(token, HttpMethod.GET, "/orders?deleted=true", null));
        if (deleteStatus == 200) {
            assertThat(active).isEmpty();
            assertThat(deleted).containsExactly(id);
        } else {
            assertThat(active).containsExactly(id);
            assertThat(deleted).isEmpty();
        }
        assertThat(exchange(token, HttpMethod.GET, "/orders/" + id, null).get("httpStatus"))
                .isEqualTo(deleteStatus == 200 ? 404 : 200);
    }

    private List<Map<String, Object>> race(Callable<Map<String, Object>> first, Callable<Map<String, Object>> second)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (Callable<Map<String, Object>> action : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Map<?, ?> create(String caller, String title, int amount) {
        Map<String, Object> response =
                exchange(caller, HttpMethod.POST, "/orders", Map.of("title", title, "amount", amount));
        assertThat(response.get("httpStatus")).isEqualTo(201);
        return (Map<?, ?>) response.get("data");
    }

    private static List<Object> ids(Map<String, Object> envelope) {
        return ((List<?>) envelope.get("data"))
                .stream().<Object>map(row -> ((Map<?, ?>) row).get("id")).toList();
    }

    private Map<String, Object> exchange(String caller, HttpMethod method, String path, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(path)
                .header("Authorization", "Bearer " + caller);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, response) -> {
            Map<String, Object> envelope = response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(envelope).isNotNull();
            assertThat(envelope.get("httpStatus"))
                    .isEqualTo(response.getStatusCode().value());
            return envelope;
        });
    }
}
