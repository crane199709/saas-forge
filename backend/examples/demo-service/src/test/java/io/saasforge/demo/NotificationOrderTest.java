package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.sdk.notification.NotificationLink;
import io.saasforge.sdk.notification.NotificationSent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationOrderTest {
    static final UUID TENANT = UUID.randomUUID();
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

    @Test
    void creatingAnOrderPublishesARealNotificationForItsCreator() throws Exception {
        TestGrants.publishAll(redisTemplate, TENANT, TestTokens.MEMBERSHIP_ID);
        ResponseEntity<Map> created = create("通知订单");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Map<?, ?> order = (Map<?, ?>) created.getBody().get("data");
        List<String> payloads = outbox.payloads(TENANT, NotificationSent.class.getSimpleName());
        assertThat(payloads).hasSize(1);
        NotificationSent sent = JsonMapper.builder().build().readValue(payloads.get(0), NotificationSent.class);
        assertThat(sent.tenantId()).isEqualTo(TENANT);
        assertThat(sent.service()).isEqualTo("demo-service");
        assertThat(sent.target().membershipIds()).containsExactly(TestTokens.MEMBERSHIP_ID);
        assertThat(sent.title()).isEqualTo("订单已创建");
        assertThat(sent.body()).isEqualTo("订单 " + order.get("number") + " 已创建");
        assertThat(sent.link()).isEqualTo(new NotificationLink("demo", "/overview"));
        assertThat(sent.id()).isNotNull();
        assertThat(sent.occurredAt()).isNotNull();
        assertThat(create(Map.of("title", "非法金额", "amount", "not-a-number"))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(outbox.payloads(TENANT, NotificationSent.class.getSimpleName()))
                .hasSize(1);
        assertThat(outbox.payloads(UUID.randomUUID(), NotificationSent.class.getSimpleName()))
                .isEmpty();
    }

    ResponseEntity<Map> create(String title) throws Exception {
        return create(Map.of("title", title, "amount", 10));
    }

    ResponseEntity<Map> create(Map<String, Object> body) throws Exception {
        String token = TestTokens.forTenant(TENANT);
        return RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri("/orders")
                .headers(h -> h.setBearerAuth(token))
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }
}
