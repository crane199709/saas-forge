package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.plan.service.PlanService;
import io.saasforge.iam.role.service.RoleService;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 先建立 V32 的真实旧库，再升级，验收公开角色服务看到的有效授权。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxReadMigrationTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-inbox-migration-signing.jwk");
    }

    @TestConfiguration
    static class Upgrade {
        @Bean
        FlywayMigrationStrategy upgradeFromV32() {
            return flyway -> {
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .target("32")
                        .load()
                        .migrate();
                var seed = new ResourceDatabasePopulator(
                        new ClassPathResource("fixtures/plans.sql"),
                        new ClassPathResource("fixtures/inbox-upgrade.sql"));
                seed.execute(flyway.getConfiguration().getDataSource());
                flyway.migrate();
            };
        }
    }

    @Autowired
    RoleService roles;

    @Autowired
    PlanService plans;

    @Test
    void upgradePreservesExistingCustomRolesAndRolelessMembersInsideTheExistingPlan() {
        assertThat(roles.grantsOf(UUID.fromString("019a0120-0000-7000-8000-0000000000a4"))
                        .permissions())
                .contains("todo:read", "notification:read", "announcement:read");
        assertThat(roles.grantsOf(UUID.fromString("019a0120-0000-7000-8000-0000000000aa"))
                        .permissions())
                .contains("todo:read", "notification:read", "announcement:read");
        assertThat(plans.permissionsOf(UUID.fromString("019a0120-0000-7000-8000-0000000000a0")))
                .hasValueSatisfying(
                        codes -> assertThat(codes).contains("todo:read", "notification:read", "announcement:read"));
    }
}
