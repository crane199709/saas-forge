package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 订单接口的信封形态与统一分页、排序约定（统一接口格式规格第 3、4 节）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000cc");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void crudResponsesAreEnvelopesAndMissingOrderIsOrderNotFound() throws Exception {
        Map<String, Object> created = exchange(HttpMethod.POST, "/orders", Map.of("title", "办公用品", "amount", 128.5));
        assertThat(created.get("code")).isEqualTo("ok");
        assertThat(created.get("httpStatus")).isEqualTo(201);
        assertThat(created.get("traceId")).asString().hasSize(32);
        assertThat(created).doesNotContainKeys("errorMessage", "userTip", "meta", "errors");
        Map<?, ?> order = (Map<?, ?>) created.get("data");
        assertThat(order.get("title")).isEqualTo("办公用品");
        assertThat(order.get("createdAt")).isNotNull();
        String id = (String) order.get("id");

        Map<?, ?> updated = exchange(HttpMethod.PUT, "/orders/" + id, Map.of("title", "改名", "amount", 1));
        assertThat(updated.get("httpStatus")).isEqualTo(200);
        assertThat(((Map<?, ?>) updated.get("data")).get("createdAt")).isEqualTo(order.get("createdAt"));

        Map<String, Object> deleted = exchange(HttpMethod.DELETE, "/orders/" + id, null);
        assertThat(deleted.get("code")).isEqualTo("ok");
        assertThat(deleted.get("httpStatus")).isEqualTo(200);
        assertThat(deleted).doesNotContainKey("data");

        Map<String, Object> missing = exchange(HttpMethod.GET, "/orders/" + id, null);
        assertThat(missing.get("code")).isEqualTo("order.notFound");
        assertThat(missing.get("httpStatus")).isEqualTo(404);
        assertThat(missing.get("userTip")).isEqualTo("订单不存在或已被删除");
        assertThat(missing.get("traceId")).asString().hasSize(32);
        assertThat(missing).doesNotContainKey("data");
    }

    @Test
    void orderListIsPagedWithDefaultsAndWhitelistedSorting() throws Exception {
        for (int amount : List.of(30, 10, 20)) {
            exchange(HttpMethod.POST, "/orders", Map.of("title", "订单 " + amount, "amount", amount));
        }

        Map<?, ?> first = exchange(HttpMethod.GET, "/orders", null);
        assertThat(first.get("httpStatus")).isEqualTo(200);
        assertThat((List<?>) first.get("data")).hasSize(3);
        assertThat(first.get("meta"))
                .isEqualTo(Map.of("page", Map.of("number", 1, "size", 20, "totalElements", 3, "totalPages", 1)));

        Map<?, ?> second = exchange(HttpMethod.GET, "/orders?page=2&size=2&sort=amount,desc", null);
        assertThat(amounts(second)).containsExactly(10);
        assertThat(((Map<?, ?>) second.get("meta")).get("page"))
                .isEqualTo(Map.of("number", 2, "size", 2, "totalElements", 3, "totalPages", 2));

        assertThat(amounts(exchange(HttpMethod.GET, "/orders?sort=amount", null)))
                .containsExactly(10, 20, 30);
        // 创建顺序为 30、10、20
        assertThat(amounts(exchange(HttpMethod.GET, "/orders?sort=createdAt,desc", null)))
                .containsExactly(20, 10, 30);
    }

    @Test
    void oversizedPageAndUnlistedSortFieldAreRejected() throws Exception {
        Map<?, ?> oversized = exchange(HttpMethod.GET, "/orders?size=101", null);
        assertThat(oversized.get("httpStatus")).isEqualTo(400);
        assertThat(oversized.get("code")).isEqualTo("request.invalid");
        assertThat(oversized.get("userTip")).isNotNull();
        assertThat((List<?>) oversized.get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .containsExactly("size");

        Map<?, ?> unlisted = exchange(HttpMethod.GET, "/orders?sort=title,asc", null);
        assertThat(unlisted.get("httpStatus")).isEqualTo(400);
        assertThat(unlisted.get("code")).isEqualTo("request.invalid");
        assertThat((List<?>) unlisted.get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .containsExactly("sort");
    }

    private static List<Integer> amounts(Map<?, ?> envelope) {
        return ((List<?>) envelope.get("data"))
                .stream()
                        .map(order -> ((Number) ((Map<?, ?>) order).get("amount")).intValue())
                        .toList();
    }

    /** 返回信封本身，并断言 HTTP 状态码与信封的 httpStatus 一致。 */
    private Map<String, Object> exchange(HttpMethod method, String uri, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(TENANT));
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
