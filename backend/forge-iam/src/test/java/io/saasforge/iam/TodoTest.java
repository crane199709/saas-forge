package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.notification.NotificationLink;
import io.saasforge.sdk.notification.NotificationTarget;
import io.saasforge.sdk.notification.TodoClosed;
import io.saasforge.sdk.notification.TodoOpened;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 通过真实事件消费入口与 HTTP 验证待办的时序与当前可见性。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql({"/fixtures/plans.sql", "/fixtures/inbox-readers.sql"})
class TodoTest {
    static final String KEY_FILE = "target/test-todo-signing.jwk";
    static final UUID TENANT = UUID.fromString("019a0120-0000-7000-8000-0000000000a0");
    static final UUID ADMIN = UUID.fromString("019a0120-0000-7000-8000-0000000000a2");
    static final UUID MEMBER = UUID.fromString("019a0120-0000-7000-8000-0000000000a4");
    static final UUID OTHER_TENANT = UUID.fromString("019a0120-0000-7000-8000-0000000000a6");
    static final UUID OTHER_MEMBER = UUID.fromString("019a0120-0000-7000-8000-0000000000aa");
    static final UUID IDENTITY = UUID.fromString("019a0120-0000-7000-8000-0000000000a3");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
    }

    @LocalServerPort
    int port;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    PermissionService permissions;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void membershipTodoIsIdempotentAndHiddenFromOtherTenants() throws Exception {
        Instant time = Instant.parse("2026-11-07T01:00:00Z");
        var event = new TodoOpened(
                TENANT,
                "demo-service",
                "order:1",
                NotificationTarget.memberships(List.of(MEMBER, OTHER_MEMBER)),
                "待处理订单",
                "请处理",
                null,
                time);
        deliver(TENANT, event);
        deliver(TENANT, event);
        assertThat(items(TENANT, MEMBER))
                .singleElement()
                .satisfies(item ->
                        assertThat(item).containsEntry("businessKey", "order:1").containsEntry("title", "待处理订单"));
        assertThat(items(OTHER_TENANT, OTHER_MEMBER)).isEmpty();
        assertThat(query(TENANT, MEMBER, "?size=1").getBody().get("meta").toString())
                .contains("totalElements=1");
        assertThat(query(TENANT, MEMBER, "?size=101").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void closeBeforeOpenLeavesATombstoneAndOnlyALaterOpenCanReopenForAllAssignees() throws Exception {
        String key = "order:reopen";
        Instant time = Instant.parse("2026-11-07T02:00:00Z");
        var target = NotificationTarget.memberships(List.of(ADMIN, MEMBER));
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time));
        deliver(TENANT, new TodoOpened(TENANT, "demo-service", key, target, "旧开启", "正文", null, time.minusSeconds(1)));
        deliver(TENANT, new TodoOpened(TENANT, "demo-service", key, target, "同时间开启", "正文", null, time));
        assertThat(titles(TENANT, MEMBER, key)).isEmpty();
        deliver(TENANT, new TodoOpened(TENANT, "demo-service", key, target, "重新开启", "正文", null, time.plusSeconds(1)));
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time));
        assertThat(titles(TENANT, MEMBER, key)).containsExactly("重新开启");
        assertThat(titles(TENANT, ADMIN, key)).containsExactly("重新开启");
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time.plusSeconds(2)));
        assertThat(titles(TENANT, MEMBER, key)).isEmpty();
        assertThat(titles(TENANT, ADMIN, key)).isEmpty();
    }

    @Test
    void permissionTargetsUseCurrentDatabaseGrantsAndPlanAndSubAppChangesApplyImmediately() throws Exception {
        permissions.report(
                "demo-service",
                new ResourceManifest(
                        "demo-service",
                        "todo-1",
                        List.of(new ResourceManifest.Permission("order:approve", "审批订单", "订单")),
                        List.of()));
        var role = mutate(
                HttpMethod.POST,
                "/roles",
                Map.of("code", "todo-approver", "name", "待办审批员", "permissions", List.of("order:approve", "todo:read")));
        String roleId = (String) ((Map<?, ?>) role.getBody().get("data")).get("id");
        mutate(HttpMethod.PUT, "/roles/memberships/" + MEMBER, Map.of("roleIds", List.of(roleId)));
        // 保留 Token 旧版本的权限快照，当前权限变化不能依赖 Redis 缓存失效。
        String stale = JsonMapper.builder()
                .build()
                .writeValueAsString(
                        new MembershipGrants(Set.of("todo-approver"), Set.of("order:approve"), null, Map.of()));
        redisTemplate.opsForValue().set(MembershipStateKeys.grants(TENANT, MEMBER, 1), stale);
        Instant time = Instant.parse("2026-11-07T03:00:00Z");
        String key = "order:permission";
        String direct = "order:direct-link";
        deliver(
                TENANT,
                new TodoOpened(
                        TENANT,
                        "demo-service",
                        key,
                        NotificationTarget.permission("order:approve"),
                        "审批订单",
                        "正文",
                        new NotificationLink("demo", "/orders"),
                        time));
        deliver(
                TENANT,
                new TodoOpened(
                        TENANT,
                        "demo-service",
                        direct,
                        NotificationTarget.memberships(List.of(MEMBER)),
                        "指定处理人",
                        "正文",
                        new NotificationLink("demo", "/orders"),
                        time));
        assertThat(titles(TENANT, MEMBER, key)).containsExactly("审批订单");
        mutate(HttpMethod.PUT, "/roles/" + roleId + "/permissions", Map.of("permissions", List.of("todo:read")));
        assertThat(titles(TENANT, MEMBER, key)).isEmpty();
        mutate(
                HttpMethod.PUT,
                "/roles/" + roleId + "/permissions",
                Map.of("permissions", List.of("order:approve", "todo:read")));
        assertThat(titles(TENANT, MEMBER, key)).containsExactly("审批订单");
        UUID plan = UUID.fromString("019a0120-0000-7000-8000-0000000000b1");
        deliver(
                TENANT,
                new TenantPlanChanged(TENANT, plan, "basic", List.of("todo:read"), List.of("demo"), null, null, 1));
        assertThat(titles(TENANT, MEMBER, key)).isEmpty();
        assertThat(titles(TENANT, MEMBER, direct)).containsExactly("指定处理人");
        deliver(
                TENANT,
                new TenantPlanChanged(
                        TENANT, plan, "basic", List.of("order:approve", "todo:read"), List.of(), null, null, 2));
        assertThat(titles(TENANT, MEMBER, key)).isEmpty();
        assertThat(titles(TENANT, MEMBER, direct)).isEmpty();
        deliver(
                TENANT,
                new TenantPlanChanged(
                        TENANT, plan, "basic", List.of("order:approve", "todo:read"), List.of("demo"), null, null, 3));
        assertThat(titles(TENANT, MEMBER, key)).containsExactly("审批订单");
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time.plusSeconds(1)));
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", direct, time.plusSeconds(1)));
    }

    @Test
    void tenantCleanupRemovesOnlyThatTenantsTodos() throws Exception {
        String key = "order:cleanup";
        Instant time = Instant.parse("2026-11-07T04:00:00Z");
        deliver(
                TENANT,
                new TodoOpened(
                        TENANT,
                        "demo-service",
                        key,
                        NotificationTarget.memberships(List.of(MEMBER)),
                        "保留",
                        "正文",
                        null,
                        time));
        deliver(
                OTHER_TENANT,
                new TodoOpened(
                        OTHER_TENANT,
                        "demo-service",
                        key,
                        NotificationTarget.memberships(List.of(OTHER_MEMBER)),
                        "清理",
                        "正文",
                        null,
                        time));
        assertThat(titles(OTHER_TENANT, OTHER_MEMBER, key)).containsExactly("清理");
        deliver(OTHER_TENANT, new TenantDeleted(OTHER_TENANT));
        deliver(OTHER_TENANT, new TenantDeleted(OTHER_TENANT));
        assertThat(titles(OTHER_TENANT, OTHER_MEMBER, key)).isEmpty();
        assertThat(titles(TENANT, MEMBER, key)).containsExactly("保留");
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time.plusSeconds(1)));
    }

    @Test
    void aStrictlyLaterInstantIsAppliedWithoutLosingNanosecondPrecision() throws Exception {
        String key = "order:nanoseconds";
        Instant time = Instant.parse("2026-11-07T05:00:00.000000100Z");
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time));
        deliver(
                TENANT,
                new TodoOpened(
                        TENANT,
                        "demo-service",
                        key,
                        NotificationTarget.memberships(List.of(MEMBER)),
                        "晚一纳秒",
                        "正文",
                        null,
                        time.plusNanos(1)));
        assertThat(titles(TENANT, MEMBER, key)).containsExactly("晚一纳秒");
        var item = items(TENANT, MEMBER).stream()
                .filter(row -> key.equals(row.get("businessKey")))
                .findFirst()
                .orElseThrow();
        assertThat(item.get("occurredAt")).isEqualTo("2026-11-07T05:00:00.000000101Z");
        deliver(
                TENANT,
                new TodoOpened(
                        TENANT,
                        "demo-service",
                        key,
                        NotificationTarget.memberships(List.of(ADMIN)),
                        "旧目标",
                        "正文",
                        null,
                        time));
        assertThat(titles(TENANT, ADMIN, key)).isEmpty();
        deliver(TENANT, new TodoClosed(TENANT, "demo-service", key, time.plusNanos(2)));
        assertThat(titles(TENANT, MEMBER, key)).isEmpty();
    }

    private ResponseEntity<Map> mutate(HttpMethod method, String path, Object body) throws Exception {
        String accessToken = token(TENANT, ADMIN);
        var response = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(path)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as(path + " " + response.getBody())
                .isTrue();
        return response;
    }

    private List<Object> titles(UUID tenant, UUID membership, String key) throws Exception {
        return items(tenant, membership).stream()
                .filter(item -> key.equals(item.get("businessKey")))
                .map(item -> item.get("title"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private <T> void deliver(UUID tenant, T event) {
        var handler = (EventHandler<T>) handlers.stream()
                .filter(candidate -> candidate.eventType() == event.getClass())
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenant, () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(UUID tenant, UUID membership) throws Exception {
        var response = query(tenant, membership, "");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private ResponseEntity<Map> query(UUID tenant, UUID membership, String suffix) throws Exception {
        String accessToken = token(tenant, membership);
        return RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/todos" + suffix)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private static String token(UUID tenant, UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        UUID identity = membership.equals(ADMIN) ? UUID.fromString("019a0120-0000-7000-8000-0000000000a1") : IDENTITY;
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(identity.toString())
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
        return jwt.serialize();
    }
}
