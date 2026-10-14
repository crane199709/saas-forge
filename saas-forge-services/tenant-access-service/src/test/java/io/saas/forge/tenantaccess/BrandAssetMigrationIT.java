package io.saas.forge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class BrandAssetMigrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("saas.forge")
            .withUsername("saas.forge_admin")
            .withPassword("admin-password")
            .withEnv("IAM_MIGRATOR_PASSWORD", "iam-migrator-password")
            .withEnv("IAM_APP_PASSWORD", "iam-app-password")
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", "tenant-access-migrator-password")
            .withEnv("TENANT_ACCESS_APP_PASSWORD", "tenant-access-app-password")
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", "entitlement-migrator-password")
            .withEnv("ENTITLEMENT_APP_PASSWORD", "entitlement-app-password")
            .withEnv("AUDIT_MIGRATOR_PASSWORD", "audit-migrator-password")
            .withEnv("AUDIT_APP_PASSWORD", "audit-app-password")
            .withCopyFileToContainer(MountableFile.forHostPath(repositoryRoot().resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @Test
    void exactPublicReadDoesNotGrantCrossTenantListingAndDisabledAssetsDisappear() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID owner = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Owner', 'ACTIVE') RETURNING id", UUID.class);
        UUID other = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Other', 'ACTIVE') RETURNING id", UUID.class);
        UUID asset = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class)).isZero();
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, owner.toString());
            jdbc.update("""
                    INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                    VALUES (?, ?, 'LOGO', ?, 80, 32, 32)
                    """, asset, owner, owner + "/" + asset + ".png");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class)).isZero();
        assertThat(jdbc.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), asset)).containsExactly(owner + "/" + asset + ".png");
        Integer otherCount = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, other.toString());
            return jdbc.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class);
        });
        assertThat(otherCount).isZero();
        migrator.update("UPDATE tenant_brand_assets SET status = 'DISABLED' WHERE id = ?", asset);
        assertThat(jdbc.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), asset)).isEmpty();
    }

    private static String url() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/tenant_access_db";
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root unavailable");
    }
}
