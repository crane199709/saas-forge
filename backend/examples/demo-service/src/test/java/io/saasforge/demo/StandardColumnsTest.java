package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderAuditMapper;
import io.saasforge.demo.order.mapper.OrderMapper;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.core.CurrentTenant;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 标准列由 SDK 统一填充、删除为逻辑删除（建表标准列规格第 4、6、8 节）。
 *
 * <p>逻辑删除后的行对 MyBatis-Plus 内置方法不可见，经测试专用的 {@link OrderAuditMapper} 手写查询读出 {@code deleted} 与审计列。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StandardColumnsTest {
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000dd");

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
    OrderAuditMapper orderAuditMapper;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void grantOrderPermissions() {
        TestGrants.publishAll(redisTemplate, TENANT, TestTokens.MEMBERSHIP_ID);
    }

    @Test
    void createFillsCreatedAndUpdatedColumnsAndUpdateRefreshesOnlyUpdatedColumns() throws Exception {
        UUID id = createOrder("办公用品");

        Row created = row(id);
        assertThat(created.createdAt()).isNotNull().isEqualTo(created.updatedAt());
        assertThat(created.createdBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(created.updatedBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(created.deleted()).isFalse();

        // 无认证上下文的修改把 updated_by 置为 NULL，下一次经接口修改须重新写回当前用户
        CurrentTenant.run(TENANT, () -> orderService.update(id, "后台修改", BigDecimal.TEN));
        Row bySystem = row(id);
        assertThat(bySystem.updatedBy()).isNull();
        assertThat(bySystem.updatedAt()).isAfter(created.updatedAt());

        exchange(HttpMethod.PUT, "/orders/" + id, Map.of("title", "改名", "amount", 1));
        Row updated = row(id);
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.createdBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(updated.updatedBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(updated.updatedAt()).isAfter(bySystem.updatedAt());
    }

    @Test
    void deletedOrderIsHiddenButRowIsMarkedDeletedWithRefreshedUpdatedColumns() throws Exception {
        UUID id = createOrder("待删除");
        UUID kept = createOrder("保留");
        CurrentTenant.run(TENANT, () -> orderService.update(id, "后台修改", BigDecimal.ONE));
        Row beforeDelete = row(id);
        assertThat(beforeDelete.updatedBy()).isNull();

        assertThat(exchange(HttpMethod.DELETE, "/orders/" + id, null).get("httpStatus"))
                .isEqualTo(200);

        assertThat(exchange(HttpMethod.GET, "/orders/" + id, null).get("code")).isEqualTo("order.notFound");
        assertThat(exchange(HttpMethod.DELETE, "/orders/" + id, null).get("code"))
                .isEqualTo("order.notFound");
        Map<String, Object> list = exchange(HttpMethod.GET, "/orders", null);
        assertThat((List<?>) list.get("data"))
                .<Object>extracting(order -> ((Map<?, ?>) order).get("id"))
                .contains(kept.toString())
                .doesNotContain(id.toString());
        assertThat(((Map<?, ?>) ((Map<?, ?>) list.get("meta")).get("page")).get("totalElements"))
                .isEqualTo(((List<?>) list.get("data")).size());

        // 删除经 deleteById(id) 完成：行仍在，且 updated_* 记录了删除人与删除时间
        Row deleted = row(id);
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.updatedBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(deleted.updatedAt()).isAfter(beforeDelete.updatedAt());
        assertThat(deleted.createdAt()).isEqualTo(beforeDelete.createdAt());
    }

    @Test
    void auditValuesSuppliedByCallerAreOverwritten() throws Exception {
        OffsetDateTime forgedTime = OffsetDateTime.parse("2000-01-01T00:00:00Z");
        UUID forgedActor = UUID.randomUUID();
        var order = new Order();
        order.setTitle("伪造审计列");
        order.setAmount(BigDecimal.ONE);
        order.setCreatedAt(forgedTime);
        order.setUpdatedAt(forgedTime);
        order.setCreatedBy(forgedActor);
        order.setUpdatedBy(forgedActor);
        asIdentity(() -> orderMapper.insert(order));

        Row inserted = row(order.getId());
        assertThat(inserted.createdAt()).isAfter(forgedTime).isEqualTo(inserted.updatedAt());
        assertThat(inserted.createdBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(inserted.updatedBy()).isEqualTo(TestTokens.IDENTITY_ID);

        // 先查后改：实体带着读出的值，再被调用方改写，修改时仍只按当前写入刷新 updated_*
        var loaded = new AtomicReference<Order>();
        CurrentTenant.run(TENANT, () -> loaded.set(orderMapper.selectById(order.getId())));
        Order toUpdate = loaded.get();
        toUpdate.setTitle("先查后改");
        toUpdate.setCreatedAt(forgedTime);
        toUpdate.setCreatedBy(forgedActor);
        toUpdate.setUpdatedAt(forgedTime);
        toUpdate.setUpdatedBy(forgedActor);
        asIdentity(() -> orderMapper.updateById(toUpdate));

        Row updated = row(order.getId());
        assertThat(updated.createdAt()).isEqualTo(inserted.createdAt());
        assertThat(updated.createdBy()).isEqualTo(TestTokens.IDENTITY_ID);
        assertThat(updated.updatedAt()).isAfter(inserted.updatedAt());
        assertThat(updated.updatedBy()).isEqualTo(TestTokens.IDENTITY_ID);
    }

    /** 模拟已认证请求线程：与资源服务器验签后放入的认证相同。 */
    private static void asIdentity(Runnable write) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(TestTokens.IDENTITY_ID.toString())
                .claim("tenant_id", TENANT.toString())
                .claim("membership_id", TestTokens.MEMBERSHIP_ID.toString())
                .claim("ver", 1)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            CurrentTenant.run(TENANT, write);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID createOrder(String title) throws Exception {
        Map<?, ?> order = (Map<?, ?>) exchange(HttpMethod.POST, "/orders", Map.of("title", title, "amount", 10))
                .get("data");
        return UUID.fromString((String) order.get("id"));
    }

    record Row(OffsetDateTime createdAt, OffsetDateTime updatedAt, UUID createdBy, UUID updatedBy, boolean deleted) {}

    private Row row(UUID id) {
        var order = new AtomicReference<Order>();
        CurrentTenant.run(TENANT, () -> order.set(orderAuditMapper.selectIncludingDeleted(id)));
        Order found = order.get();
        assertThat(found).isNotNull();
        return new Row(
                found.getCreatedAt(),
                found.getUpdatedAt(),
                found.getCreatedBy(),
                found.getUpdatedBy(),
                found.getDeleted());
    }

    private Map<String, Object> exchange(HttpMethod method, String uri, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(TENANT));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange(
                (req, response) -> response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {}));
    }
}
