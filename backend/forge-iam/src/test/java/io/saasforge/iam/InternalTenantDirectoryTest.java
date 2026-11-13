package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

/** Tenant Task 通过服务令牌读取 IAM 租户目录，不取得租户名称或其他资料。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/internal_tenants.sql")
class InternalTenantDirectoryTest {
    static final String SERVICE_TOKEN = "directory-service-token";
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.signing-key-file", () -> "target/test-directory-signing.jwk");
        registry.add("forge.iam.manifest.tokens.directory-service", () -> SERVICE_TOKEN);
    }

    @LocalServerPort
    int port;

    @Test
    void serviceReadsOnlyIdsOfMatchingUndeletedTenants() {
        var response = get("/internal/tenants?status=trial,active", SERVICE_TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("code")).isEqualTo("ok");
        assertThat(response.getBody().get("data"))
                .isEqualTo(List.of(
                        "019a0110-0000-7000-8000-000000000011",
                        "019a0110-0000-7000-8000-000000000012",
                        "019a0110-0000-7000-8000-000000000013"));
        assertThat(get("/internal/tenants?status=trial", SERVICE_TOKEN)
                        .getBody()
                        .get("data"))
                .isEqualTo(List.of("019a0110-0000-7000-8000-000000000013"));
    }

    @Test
    void pagesCoverEveryMatchingTenantExactlyOnceInIdOrder() {
        var first = get("/internal/tenants?status=trial,active&page=1&size=2", SERVICE_TOKEN)
                .getBody();
        var second = get("/internal/tenants?status=trial,active&page=2&size=2", SERVICE_TOKEN)
                .getBody();
        var exhausted = get("/internal/tenants?status=trial,active&page=3&size=2", SERVICE_TOKEN)
                .getBody();

        assertThat(first.get("data"))
                .isEqualTo(List.of("019a0110-0000-7000-8000-000000000011", "019a0110-0000-7000-8000-000000000012"));
        assertThat(second.get("data")).isEqualTo(List.of("019a0110-0000-7000-8000-000000000013"));
        assertThat(exhausted.get("data")).isEqualTo(List.of());
        assertThat(first.get("meta"))
                .isEqualTo(Map.of("page", Map.of("number", 1, "size", 2, "totalElements", 3, "totalPages", 2)));
        assertThat(second.get("meta"))
                .isEqualTo(Map.of("page", Map.of("number", 2, "size", 2, "totalElements", 3, "totalPages", 2)));
        assertThat(get("/internal/tenants?status=trial,active&page=1&size=2", SERVICE_TOKEN)
                        .getBody()
                        .get("data"))
                .isEqualTo(first.get("data"));
    }

    @Test
    void rejectsInvalidStatusFiltersAndPagination() {
        for (String query : List.of(
                "status=", "status=trial,unknown", "status=trial,", "status=trial&page=0", "status=trial&size=101")) {
            var response = get("/internal/tenants?" + query, SERVICE_TOKEN);
            assertThat(response.getStatusCode().value()).as(query).isEqualTo(400);
            assertThat(response.getBody().get("code")).as(query).isEqualTo("request.invalid");
        }
    }

    @Test
    void requiresAConfiguredServiceToken() {
        for (String token : new String[] {null, "wrong-service-token"}) {
            var response = get("/internal/tenants?status=trial,active", token);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody().get("code")).isEqualTo("auth.unauthenticated");
        }
    }

    private ResponseEntity<Map<String, Object>> get(String uri, String serviceToken) {
        return RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri(uri)
                .headers(headers -> {
                    if (serviceToken != null) {
                        headers.setBearerAuth(serviceToken);
                    }
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(ENVELOPE);
    }
}
