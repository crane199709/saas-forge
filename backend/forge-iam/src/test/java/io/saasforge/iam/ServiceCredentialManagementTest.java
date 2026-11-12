package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceCredentialManagementTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final String ACTOR = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.signing-key-file", () -> "target/test-credential-management-signing.jwk");
        registry.add("forge.ops.token", () -> "iam-management-ops-token");
    }

    @LocalServerPort
    int port;

    RestClient client() {
        return RestClient.create("http://127.0.0.1:" + port);
    }

    Map post(String path, Object body, int status) {
        return client().post()
                .uri("/internal/ops/service-credentials" + path)
                .headers(h -> h.setBearerAuth("iam-management-ops-token"))
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(status);
                    return response.bodyTo(Map.class);
                });
    }

    @Test
    void opsCredentialIsRequiredAndManifestCredentialCannotManage() {
        int status = client().get()
                .uri("/internal/ops/service-credentials")
                .headers(h -> h.setBearerAuth("demo-token"))
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(401);
    }

    @Test
    void invalidServiceAndMissingTrustedActorAreRejected() {
        assertThat(post("", Map.of("service", "Invalid_Name", "actor", ACTOR), 400)
                        .get("code"))
                .isEqualTo("service-credential.invalidService");
        post("", Map.of("service", "valid-service"), 400);
        post("", Map.of("service", "a".repeat(101), "actor", ACTOR), 400);
    }

    @Test
    void createListsOnlyFingerprintAndDisableOrRotateImmediatelyRevokesOldSecret() {
        var data = (Map<?, ?>) post("", Map.of("service", "new-unreported-service", "actor", ACTOR), 201)
                .get("data");
        assertThat(data.get("manifestReported")).isEqualTo(false);
        String token = (String) data.get("token");
        String id = ((Map<?, ?>) data.get("credential")).get("id").toString();
        assertThat(authenticate(token)).isEqualTo(200);
        String list = client().get()
                .uri("/internal/ops/service-credentials?status=active")
                .headers(h -> h.setBearerAuth("iam-management-ops-token"))
                .retrieve()
                .body(String.class);
        assertThat(list).contains(id).doesNotContain(token).doesNotContain("digest");
        var rotated = (Map<?, ?>)
                post("/" + id + "/rotate", Map.of("actor", ACTOR), 201).get("data");
        assertThat(authenticate(token)).isEqualTo(401);
        String next = (String) rotated.get("token");
        assertThat(authenticate(next)).isEqualTo(200);
        String nextId = ((Map<?, ?>) rotated.get("credential")).get("id").toString();
        post("/" + nextId + "/disable", Map.of("actor", ACTOR), 200);
        assertThat(authenticate(next)).isEqualTo(401);
        String active = client().get()
                .uri("/internal/ops/service-credentials?status=active")
                .headers(h -> h.setBearerAuth("iam-management-ops-token"))
                .retrieve()
                .body(String.class);
        assertThat(active).doesNotContain(id).doesNotContain(nextId);
    }

    int authenticate(String token) {
        return client().get()
                .uri("/internal/services")
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }
}
