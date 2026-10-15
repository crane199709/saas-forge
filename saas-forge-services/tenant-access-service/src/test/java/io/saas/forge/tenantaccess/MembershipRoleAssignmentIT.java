package io.saas.forge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.MembershipRoleService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcPermissionAuthority;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MembershipRoleAssignmentIT {
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
            .withCopyFileToContainer(MountableFile.forHostPath(root().resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @Test
    void administratorCanAssignAndRevokeRolesButCannotRemoveLastAdministrator() throws Exception {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/tenant_access_db";
        Flyway.configure().dataSource(url, "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(url, "tenant_access_migrator", "tenant-access-migrator-password");
                var statement = connection.prepareStatement("SELECT import_permission_catalog(?::jsonb, ?, ?)")) {
            statement.setString(1, Files.readString(root().resolve(
                    "saas-forge-services/tenant-access-service/permission-catalog/v1.json")));
            statement.setString(2, "a".repeat(64));
            statement.setString(3, "saas.forge.dev.tenant-access-service.events");
            statement.execute();
        }
        var app = new DriverManagerDataSource(url, "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(app);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(app));
        UUID tenant = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        UUID adminIdentity = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        UUID memberIdentity = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        UUID adminMembership = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        UUID memberMembership = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        UUID adminRole = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        UUID readerRole = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        try (var connection = java.sql.DriverManager.getConnection(url, "tenant_access_migrator", "tenant-access-migrator-password")) {
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO tenants (id, display_name, tenant_status) VALUES ('" + tenant + "', 'Assignment test', 'ACTIVE')");
                statement.execute("INSERT INTO memberships (id, tenant_id, identity_id, membership_status) VALUES "
                        + "('" + adminMembership + "', '" + tenant + "', '" + adminIdentity + "', 'ENABLED'),"
                        + "('" + memberMembership + "', '" + tenant + "', '" + memberIdentity + "', 'ENABLED')");
                statement.execute("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES "
                        + "('" + adminRole + "', '" + tenant + "', 'TENANT_ADMINISTRATOR', true, now()),"
                        + "('" + readerRole + "', '" + tenant + "', 'READER', false, now())");
                statement.execute("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES "
                        + "('" + tenant + "', '" + adminMembership + "', '" + adminRole + "', now())");
                statement.execute("INSERT INTO permission_catalog (code, semantic_id, source, enabled, first_revision, last_revision, updated_at) "
                        + "VALUES ('example:project:list', 'listProjects', 'example-service', true, 1, 1, now())");
            }
        }
        var admin = new TenantContextSnapshot(adminIdentity, adminMembership, tenant);
        var member = new TenantContextSnapshot(memberIdentity, memberMembership, tenant);
        var authority = new JdbcPermissionAuthority(jdbc);
        var repository = new JdbcMembershipRoleRepository(jdbc);
        var ids = new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
        var service = new MembershipRoleService(authority, repository,
                event -> jdbc.update("INSERT INTO tenant_access_outbox_events (event_id, tenant_id, occurred_at, topic, ordering_key, event_snapshot, next_attempt_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", event.eventId(), event.tenantId(),
                        event.occurredAt().atOffset(java.time.ZoneOffset.UTC), event.topic(), event.orderingKey(),
                        event.eventSnapshot(), event.occurredAt().atOffset(java.time.ZoneOffset.UTC)),
                ids, Clock.systemUTC(), new ObjectMapper(), "saas.forge.dev.tenant-access-service.events");
        assertThat(Boolean.TRUE.equals(tx.execute(status -> authority.allowed(member, "example:project:list")))).isFalse();
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            jdbc.update("INSERT INTO tenant_role_permissions (tenant_id, role_id, permission_code, assigned_at) "
                    + "VALUES (?, ?, 'example:project:list', now())", tenant, readerRole);
        });
        tx.execute(status -> service.assign(admin, memberMembership, readerRole));
        assertThat(Boolean.TRUE.equals(tx.execute(status -> authority.allowed(member, "example:project:list")))).isTrue();
        assertThatThrownBy(() -> tx.execute(status -> service.assign(member, memberMembership, adminRole)))
                .hasMessageContaining("TENANT_ADMINISTRATOR_REQUIRED");
        assertThatThrownBy(() -> tx.execute(status -> service.revoke(admin, adminMembership, adminRole)))
                .hasMessageContaining("LAST_TENANT_ADMINISTRATOR");
        tx.execute(status -> service.revoke(admin, memberMembership, readerRole));
        assertThat(Boolean.TRUE.equals(tx.execute(status -> authority.allowed(member, "example:project:list")))).isFalse();
        List<JdbcMembershipRoleRepository.Assignment> remaining =
                tx.execute(status -> service.list(admin, memberMembership));
        assertThat(remaining).isEmpty();
        var roles = new JdbcTenantRoleRepository(jdbc);
        UUID delegated = tx.execute(status -> roles.create(tenant, "INVITATION_MANAGER",
                List.of("tenant-access:invitation:manage")));
        tx.execute(status -> service.assign(admin, memberMembership, delegated));
        tx.execute(status -> service.assign(admin, memberMembership, readerRole));
        assertThat(Boolean.TRUE.equals(tx.execute(status -> authority.allowed(member,
                "tenant-access:invitation:manage")))).isTrue();
        assertThat(Boolean.TRUE.equals(tx.execute(status -> authority.allowed(member,
                "example:project:list")))).isTrue();
        assertThat(Boolean.TRUE.equals(tx.execute(status -> authority.isAdministrator(member)))).isFalse();
        assertThatThrownBy(() -> tx.execute(status -> roles.create(tenant, "BRAND_MANAGER",
                List.of("system:brand:manage")))).hasMessageContaining("ADMIN_PERMISSION_RESERVED");

        tx.execute(status -> service.assign(admin, memberMembership, adminRole));
        int before = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return repository.enabledAdministrators(tenant);
        });
        assertThat(before).isEqualTo(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                start.await();
                return revokeOutcome(tx, service, admin, adminMembership, adminRole);
            });
            var second = workers.submit(() -> {
                start.await();
                return revokeOutcome(tx, service, member, memberMembership, adminRole);
            });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }
        int after = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return repository.enabledAdministrators(tenant);
        });
        assertThat(after).isEqualTo(1);
        Integer facts = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return jdbc.queryForObject("SELECT count(*) FROM tenant_access_outbox_events WHERE tenant_id = ? "
                    + "AND event_snapshot->>'type' = 'com.saas.forge.membership-role.changed.v1'",
                    Integer.class, tenant);
        });
        assertThat(facts).isEqualTo(6);
        UUID noopRole = tx.execute(status -> roles.create(tenant, "NOOP", List.of()));
        var remainingAdmin = Boolean.TRUE.equals(tx.execute(status -> authority.isAdministrator(admin)))
                ? admin : member;
        var failedOutbox = new MembershipRoleService(authority, repository,
                event -> { throw new IllegalStateException("outbox unavailable"); },
                ids, Clock.systemUTC(), new ObjectMapper(), "saas.forge.dev.tenant-access-service.events");
        assertThatThrownBy(() -> tx.execute(status -> failedOutbox.assign(
                remainingAdmin, memberMembership, noopRole))).hasMessageContaining("outbox unavailable");
        List<JdbcMembershipRoleRepository.Assignment> afterFailure =
                tx.execute(status -> service.list(remainingAdmin, memberMembership));
        assertThat(afterFailure).noneMatch(assignment -> assignment.roleId().equals(noopRole));
    }

    private static boolean revokeOutcome(TransactionTemplate tx, MembershipRoleService service,
            TenantContextSnapshot actor, UUID membershipId, UUID roleId) {
        try {
            tx.execute(status -> service.revoke(actor, membershipId, roleId));
            return true;
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            return false;
        }
    }

    private static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root unavailable");
    }
}
