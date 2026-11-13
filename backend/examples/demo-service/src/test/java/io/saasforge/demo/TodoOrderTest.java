package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.sdk.notification.TodoClosed;
import io.saasforge.sdk.notification.TodoOpened;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 用真实订单 HTTP 操作观察 SDK Outbox 传输边界；跨服务 Broker 链路由票 05 验收。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TodoOrderTest {
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000d3");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        TestTokens.register(registry);
        TestReferenceData.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void grants() {
        TestGrants.publishAll(redisTemplate, TENANT, TestTokens.MEMBERSHIP_ID);
    }

    @Test
    void creatingAnOrderOpensATodoForItsCreator() throws Exception {
        Map<?, ?> order = (Map<?, ?>) exchange(HttpMethod.POST, "/orders", Map.of("title", "待处理订单", "amount", 12))
                .get("data");
        String key = "order:" + order.get("id");
        List<TodoOpened> opened = outbox.payloads(TENANT, "TodoOpened").stream()
                .map(payload -> json.readValue(payload, TodoOpened.class))
                .filter(event -> key.equals(event.businessKey()))
                .toList();
        assertThat(opened).singleElement().satisfies(event -> {
            assertThat(event.tenantId()).isEqualTo(TENANT);
            assertThat(event.service()).isEqualTo("demo-service");
            assertThat(event.target().membershipIds()).containsExactly(TestTokens.MEMBERSHIP_ID);
            assertThat(event.title()).isEqualTo("处理订单 " + order.get("number"));
            assertThat(event.body()).isEqualTo("待处理订单");
            assertThat(event.link().appCode()).isEqualTo("demo");
            assertThat(event.link().route()).isEqualTo("/overview");
        });
    }

    @Test
    void closingAnOrderClosesItsTodoOnlyOnceAndDeletionAlsoClosesTheTodo() throws Exception {
        Map<?, ?> order = (Map<?, ?>) exchange(HttpMethod.POST, "/orders", Map.of("title", "关闭订单", "amount", 15))
                .get("data");
        String id = (String) order.get("id");
        Map<?, ?> closed = (Map<?, ?>)
                exchange(HttpMethod.POST, "/orders/" + id + "/close", null).get("data");
        assertThat(closed.get("status")).isEqualTo("closed");
        exchange(HttpMethod.POST, "/orders/" + id + "/close", null);
        assertThat(closedEvents(id)).hasSize(1);
        TodoOpened opened = outbox.payloads(TENANT, "TodoOpened").stream()
                .map(payload -> json.readValue(payload, TodoOpened.class))
                .filter(event -> event.businessKey().equals("order:" + id))
                .findFirst()
                .orElseThrow();
        assertThat(closedEvents(id).get(0).occurredAt()).isAfter(opened.occurredAt());
        Map<?, ?> removed = (Map<?, ?>) exchange(HttpMethod.POST, "/orders", Map.of("title", "删除订单", "amount", 15))
                .get("data");
        String removedId = (String) removed.get("id");
        exchange(HttpMethod.DELETE, "/orders/" + removedId, null);
        assertThat(closedEvents(removedId)).singleElement().satisfies(event -> {
            assertThat(event.tenantId()).isEqualTo(TENANT);
            assertThat(event.service()).isEqualTo("demo-service");
        });
    }

    private List<TodoClosed> closedEvents(String id) {
        return outbox.payloads(TENANT, "TodoClosed").stream()
                .map(payload -> json.readValue(payload, TodoClosed.class))
                .filter(event -> event.businessKey().equals("order:" + id))
                .toList();
    }

    private Map<?, ?> exchange(HttpMethod method, String path, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(path)
                .header("Authorization", "Bearer " + TestTokens.forTenant(TENANT));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        var response = request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as(path + " " + response.getBody())
                .isTrue();
        return response.getBody();
    }
}
