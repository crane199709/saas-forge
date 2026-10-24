package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 用 compose 同一份初始化脚本建库，验证「迁移账号跑 Flyway、运行账号连应用」的分离确实生效。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init/01-service-schemas.sql"),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationsRunAsMigratorAccount() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty().allSatisfy(migration -> {
            assertThat(migration.getState().isFailed()).isFalse();
            assertThat(migration.getInstalledBy()).isEqualTo("demo_migrator");
        });
    }

    @Test
    void applicationDataSourceCannotChangeSchema() {
        // 用应用数据源跑迁移：Flyway 需要先建一张新的历史表（schema 已非空，需 baselineOnMigrate 才会去建），
        // 运行账号没有 DDL 权限，应被数据库拒绝
        Flyway withApplicationAccount = Flyway.configure()
                .dataSource(dataSource)
                .schemas("demo")
                .table("probe_schema_history")
                .baselineOnMigrate(true)
                .load();

        assertThatThrownBy(withApplicationAccount::migrate)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo("42501");
    }
}
