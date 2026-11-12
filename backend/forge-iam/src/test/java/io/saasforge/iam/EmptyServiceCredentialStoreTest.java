package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.servicecredential.config.ManifestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** 数据库和引导配置都为空的 IAM 可以启动，但不接受任何服务凭证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmptyServiceCredentialStoreTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.signing-key-file", () -> "target/test-empty-credentials-signing.jwk");
        // 屏蔽执行测试的终端可能注入的本地引导凭证。
        registry.add("forge.iam.manifest.tokens.demo-service", () -> "");
        registry.add("forge.iam.manifest.tokens.lowcode-service", () -> "");
        registry.add("forge.iam.manifest.tokens.forge-platform", () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    ManifestProperties properties;

    @Test
    void emptyCredentialsAllowStartupAndRejectInternalCalls() {
        assertThat(properties.tokens()).isEmpty();
        for (String path : new String[] {
            "/internal/services", "/internal/permissions", "/internal/permission-tree", "/internal/tenants"
        }) {
            var response = RestClient.create("http://127.0.0.1:" + port)
                    .get()
                    .uri(path)
                    .headers(headers -> headers.setBearerAuth("local-demo-manifest-token"))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, result) -> {})
                    .toBodilessEntity();
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(401);
        }
    }
}
