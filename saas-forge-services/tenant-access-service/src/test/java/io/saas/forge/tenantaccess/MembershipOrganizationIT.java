package io.saas.forge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipOrganizationRepository;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class MembershipOrganizationIT {
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
    void disabledMembershipCanHaveMultipleUnitsAndMoveWithoutChangingAuthorizationOrQuota() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('A', 'ACTIVE') RETURNING id", UUID.class);
        UUID other = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('B', 'ACTIVE') RETURNING id", UUID.class);
        UUID identity = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID membership = migrator.queryForObject(
                "INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES (?, ?, 'DISABLED') RETURNING id",
                UUID.class, tenant, identity);
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var ids = new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
        var units = new JdbcOrganizationUnitRepository(jdbc, ids);
        var assignments = new JdbcMembershipOrganizationRepository(jdbc);
        var rootA = tx.execute(status -> units.create(tenant, "A", null));
        var rootB = tx.execute(status -> units.create(tenant, "B", null));
        var child = tx.execute(status -> units.create(tenant, "Child", rootA.id()));
        var foreign = tx.execute(status -> units.create(other, "Foreign", null));
        int membershipsBefore = migrator.queryForObject("SELECT count(*) FROM memberships", Integer.class);
        int grantsBefore = migrator.queryForObject("SELECT count(*) FROM membership_role_assignments", Integer.class);

        tx.executeWithoutResult(status -> assignments.add(tenant, membership, rootA.id()));
        tx.executeWithoutResult(status -> assignments.add(tenant, membership, child.id()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                assignments.add(tenant, membership, rootA.id())))
                .isInstanceOf(OrganizationUnitService.Conflict.class);
        java.util.List<UUID> initial = tx.execute(status -> assignments.list(tenant, membership));
        assertThat(initial)
                .containsExactlyInAnyOrder(rootA.id(), child.id());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> assignments.add(tenant, membership, foreign.id())))
                .isInstanceOf(OrganizationUnitService.Missing.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> assignments.add(other, membership, foreign.id())))
                .isInstanceOf(OrganizationUnitService.Missing.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> assignments.list(other, membership)))
                .isInstanceOf(OrganizationUnitService.Missing.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> units.delete(tenant, child.id())))
                .isInstanceOf(OrganizationUnitService.Conflict.class);
        tx.execute(status -> units.move(tenant, child.id(), rootB.id()));
        java.util.List<UUID> moved = tx.execute(status -> assignments.list(tenant, membership));
        assertThat(moved)
                .containsExactlyInAnyOrder(rootA.id(), child.id());
        tx.executeWithoutResult(status -> assignments.remove(tenant, membership, rootA.id()));
        tx.executeWithoutResult(status -> assignments.remove(tenant, membership, child.id()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                assignments.remove(tenant, membership, child.id())))
                .isInstanceOf(OrganizationUnitService.Missing.class);
        java.util.List<UUID> removed = tx.execute(status -> assignments.list(tenant, membership));
        assertThat(removed).isEmpty();
        assertThat(migrator.queryForObject("SELECT count(*) FROM memberships", Integer.class))
                .isEqualTo(membershipsBefore);
        assertThat(migrator.queryForObject("SELECT count(*) FROM membership_role_assignments", Integer.class))
                .isEqualTo(grantsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_unit_memberships", Integer.class)).isZero();
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
