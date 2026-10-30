#set( $dollar = '$' )
#set( $hash = '#' )
package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 接口按 Permission Code 拦截，以及 ${serviceName} 申报的 Resource Manifest（工单 12）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FunctionalPermissionTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000ee");
    private static final UUID READER = UUID.fromString("019a0110-0000-7000-8000-0000000000e1");
    private static final UUID OUTSIDER = UUID.fromString("019a0110-0000-7000-8000-0000000000e2");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("postgres"), "/docker-entrypoint-initdb.d/");

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

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void membershipWithThePermissionGets200AndWithoutGets403OnTheSameEndpoint() throws Exception {
        TestGrants.publish(redisTemplate, TENANT, READER, List.of("order:read"));
        TestGrants.publish(redisTemplate, TENANT, OUTSIDER, List.of());

        var allowed = get("/orders", READER);
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody().get("code")).isEqualTo("ok");

        var denied = get("/orders", OUTSIDER);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(denied.getBody().get("code")).isEqualTo("auth.forbidden");
        assertThat(denied.getBody().get("userTip")).isEqualTo("没有权限执行该操作");

        // 只有 order:read 的成员不能创建订单。
        int created = client(READER)
                .post()
                .uri("/orders")
                .body(Map.of("title", "越权", "amount", 1))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toBodilessEntity()
                .getStatusCode()
                .value();
        assertThat(created).isEqualTo(403);

        // 未声明权限码的接口只要求已认证。
        assertThat(get("/context", OUTSIDER).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void manifestDeclaresEveryAnnotatedPermissionWithNamesAndTheMenu() {
        ResourceManifest manifest = ResourceManifestCollector.collect(
                "${serviceName}", "1", "${package}", getClass().getClassLoader());

        assertThat(manifest.permissions())
                .containsExactly(
                        new Permission("order:create", "创建订单", "订单"),
                        new Permission("order:delete", "删除订单", "订单"),
                        new Permission("order:read", "查看订单", "订单"),
                        new Permission("order:update", "修改订单", "订单"));
        var leaf = manifest.menus().get(0).children().get(0);
        assertThat(leaf.app()).isEqualTo("${pathPrefix}");
        assertThat(leaf.path()).isEqualTo("overview");
        assertThat(leaf.permission()).isEqualTo("order:read");
    }

    private ResponseEntity<Map> get(String uri, UUID membership) throws Exception {
        return client(membership)
                .get()
                .uri(uri)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(Map.class);
    }

    private RestClient client(UUID membership) throws Exception {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestTokens.forMembership(TENANT, membership))
                .build();
    }
}
