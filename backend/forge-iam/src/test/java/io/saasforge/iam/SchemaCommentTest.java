package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.schema.mapper.SchemaCommentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Schema 标准门禁：检查中文注释，以及通知父表和月分区的强制租户 RLS。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class SchemaCommentTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("forge.iam.signing-key-file", () -> "target/test-schema-comment-signing.jwk");
    }

    @Autowired
    SchemaCommentMapper mapper;

    @Test
    void notificationParentsAndMonthlyPartitionsRequireForcedTenantRls() {
        assertThat(mapper.selectNotificationTables())
                .contains("notification", "notification_recipient")
                .hasSizeGreaterThanOrEqualTo(6);
        assertThat(mapper.selectUnprotectedNotificationTables()).isEmpty();
    }

    @Test
    void everyTableAndColumnHasComment() {
        assertThat(mapper.countTables()).isPositive();
        assertThat(mapper.selectUncommented()).isEmpty();
    }
}
