package io.saas.forge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcOrganizationUnitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class OrganizationUnitMigrationIT {
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
    void multipleTreesCanMoveWithoutLosingMembershipAndRejectInvalidChanges() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('A', 'ACTIVE') RETURNING id", UUID.class);
        UUID other = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('B', 'ACTIVE') RETURNING id", UUID.class);
        assertThat(migrator.queryForObject("SELECT count(*) FROM organizations WHERE tenant_id IN (?, ?)",
                Integer.class, tenant, other)).isEqualTo(2);

        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var units = new JdbcOrganizationUnitRepository(jdbc,
                new UuidV7Generator(Clock.systemUTC(), new SecureRandom()));
        UUID newTenant = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        Integer createdOrganization = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, newTenant.toString());
            jdbc.update("INSERT INTO tenants (id, display_name, tenant_status) VALUES (?, 'C', 'PENDING')", newTenant);
            return jdbc.queryForObject("SELECT count(*) FROM organizations WHERE tenant_id = ?", Integer.class,
                    newTenant);
        });
        assertThat(createdOrganization).isEqualTo(1);
        var rootA = tx.execute(status -> units.create(tenant, "Root A", null));
        var rootB = tx.execute(status -> units.create(tenant, "Root B", null));
        var child = tx.execute(status -> units.create(tenant, "Child", rootA.id()));
        var leaf = tx.execute(status -> units.create(tenant, "Leaf", child.id()));
        java.util.List<io.saas.forge.tenantaccess.application.organization.OrganizationUnit> initial =
                tx.execute(status -> units.list(tenant));
        assertThat(initial).hasSize(4);
        assertThatThrownBy(() -> tx.execute(status -> units.move(tenant, rootA.id(), leaf.id())))
                .isInstanceOf(OrganizationUnitService.Conflict.class);
        assertThatThrownBy(() -> tx.execute(status -> units.move(other, child.id(), null)))
                .isInstanceOf(OrganizationUnitService.Missing.class);
        assertThatThrownBy(() -> tx.execute(status -> { units.delete(tenant, rootA.id()); return null; }))
                .isInstanceOf(OrganizationUnitService.Conflict.class);

        UUID identity = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID member = migrator.queryForObject(
                "INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES (?, ?, 'DISABLED') RETURNING id",
                UUID.class, tenant, identity);
        migrator.update("INSERT INTO organization_unit_memberships (tenant_id, unit_id, membership_id) VALUES (?, ?, ?)",
                tenant, leaf.id(), member);
        assertThatThrownBy(() -> tx.execute(status -> { units.delete(tenant, leaf.id()); return null; }))
                .isInstanceOf(OrganizationUnitService.Conflict.class);
        assertThat(tx.execute(status -> units.move(tenant, child.id(), rootB.id())).parentId()).isEqualTo(rootB.id());
        java.util.List<io.saas.forge.tenantaccess.application.organization.OrganizationUnit> moved =
                tx.execute(status -> units.list(tenant));
        assertThat(moved).filteredOn(item -> item.id().equals(leaf.id()))
                .singleElement().satisfies(item -> assertThat(item.memberCount()).isEqualTo(1));
        assertThat(tx.execute(status -> units.move(tenant, child.id(), null)).parentId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_units", Integer.class)).isZero();
    }

    private static String url() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/tenant_access_db";
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
