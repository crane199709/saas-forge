package io.saas.forge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcPermissionAuthority;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository;
import io.saas.forge.tenantaccess.application.authorization.TenantRoleService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PermissionCatalogMigrationIT {
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
    @Order(2)
    void customRoleGrantsOnlyItsTenantAndRejectsAssignedDeletion() throws Exception {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        UUID tenantId;
        UUID identityId;
        UUID membershipId;
        try (var connection = DriverManager.getConnection(url(), "tenant_access_migrator", "tenant-access-migrator-password");
                var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("INSERT INTO tenants (display_name, tenant_status) VALUES ('Role test', 'ACTIVE') RETURNING id")) {
                row.next(); tenantId = row.getObject(1, UUID.class);
            }
            try (var row = statement.executeQuery("SELECT uuidv7()")) {
                row.next(); identityId = row.getObject(1, UUID.class);
            }
            try (var row = statement.executeQuery("INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES ('"
                    + tenantId + "', '" + identityId + "', 'ENABLED') RETURNING id")) {
                row.next(); membershipId = row.getObject(1, UUID.class);
            }
            statement.execute("INSERT INTO permission_catalog (code, semantic_id, source, enabled, first_revision, last_revision, updated_at) "
                    + "VALUES ('example:project:list', 'listProjects', 'example-service', true, 1, 1, now()) "
                    + "ON CONFLICT (code) DO NOTHING");
        }
        var dataSource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var roles = new JdbcTenantRoleRepository(jdbc);
        var authority = new JdbcPermissionAuthority(jdbc);
        var context = new TenantContextSnapshot(identityId, membershipId, tenantId);
        UUID roleId = transactions.execute(status -> roles.create(context.tenantId(), "REPORT_READER",
                java.util.List.of("example:project:list")));
        assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(context, "example:project:list")))).isFalse();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES (?, ?, ?, now())",
                    tenantId, membershipId, roleId);
        });
        assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(context, "example:project:list")))).isTrue();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> roles.delete(tenantId, roleId)))
                .hasMessageContaining("ROLE_ASSIGNED");
        transactions.executeWithoutResult(status -> roles.update(tenantId, roleId, "REPORT_READER", java.util.List.of()));
        assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(context, "example:project:list")))).isFalse();
        java.util.List<JdbcTenantRoleRepository.Role> listed = transactions.execute(status -> roles.list(tenantId));
        assertThat(listed).hasSize(1);
        UUID otherTenant;
        UUID systemRole;
        try (var connection = DriverManager.getConnection(url(), "tenant_access_migrator", "tenant-access-migrator-password");
                var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("INSERT INTO tenants (display_name, tenant_status) VALUES ('Other role tenant', 'ACTIVE') RETURNING id")) {
                row.next(); otherTenant = row.getObject(1, UUID.class);
            }
            try (var row = statement.executeQuery("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) "
                    + "VALUES (uuidv7(), '" + tenantId + "', 'TENANT_ADMINISTRATOR', true, now()) RETURNING id")) {
                row.next(); systemRole = row.getObject(1, UUID.class);
            }
        }
        assertThatThrownBy(() -> transactions.execute(status -> roles.find(otherTenant, roleId)))
                .hasMessageContaining("ROLE_NOT_FOUND");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> roles.delete(tenantId, systemRole)))
                .hasMessageContaining("SYSTEM_ROLE_IMMUTABLE");
        assertThatThrownBy(() -> transactions.execute(status -> roles.update(tenantId, systemRole,
                "RENAMED_ADMIN", java.util.List.of())))
                .hasMessageContaining("SYSTEM_ROLE_IMMUTABLE");
    }

    @Test
    @Order(3)
    void committedRoleMutationWritesOutboxAndFailedOutboxRollsBackRole() throws Exception {
        UUID tenantId;
        UUID actorId;
        UUID membershipId;
        try (var connection = DriverManager.getConnection(url(), "tenant_access_migrator", "tenant-access-migrator-password");
                var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("INSERT INTO tenants (display_name, tenant_status) VALUES ('Role outbox', 'ACTIVE') RETURNING id")) {
                row.next(); tenantId = row.getObject(1, UUID.class);
            }
            try (var row = statement.executeQuery("SELECT uuidv7()")) {
                row.next(); actorId = row.getObject(1, UUID.class);
            }
            try (var row = statement.executeQuery("INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES ('"
                    + tenantId + "', '" + actorId + "', 'ENABLED') RETURNING id")) {
                row.next(); membershipId = row.getObject(1, UUID.class);
            }
            try (var row = statement.executeQuery("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) "
                    + "VALUES (uuidv7(), '" + tenantId + "', 'TENANT_ADMINISTRATOR', true, now()) RETURNING id")) {
                row.next();
                statement.execute("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) "
                        + "VALUES ('" + tenantId + "', '" + membershipId + "', '" + row.getObject(1, UUID.class) + "', now())");
            }
        }
        var dataSource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        OutboxEventRepository outbox = event -> jdbc.update("""
                INSERT INTO tenant_access_outbox_events
                (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, event.eventId(), event.tenantId(), event.occurredAt().atOffset(java.time.ZoneOffset.UTC), event.topic(),
                event.orderingKey(), event.traceId(), event.eventSnapshot(), event.occurredAt().atOffset(java.time.ZoneOffset.UTC));
        var ids = new UuidV7Generator(java.time.Clock.systemUTC(), new java.security.SecureRandom());
        var roles = new JdbcTenantRoleRepository(jdbc);
        var authority = new JdbcPermissionAuthority(jdbc);
        var context = new TenantContextSnapshot(actorId, membershipId, tenantId);
        var service = new TenantRoleService(authority, roles, outbox, ids, java.time.Clock.systemUTC(),
                new ObjectMapper(), "saas.forge.dev.tenant-access-service.events");
        transactions.execute(status -> service.create(context, "AUDITED_READER", java.util.List.of("example:project:list")));
        String eventType = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT event_snapshot->>'type' FROM tenant_access_outbox_events WHERE tenant_id = ?",
                    String.class, tenantId);
        });
        assertThat(eventType).isEqualTo("com.saas.forge.tenant-role.changed.v1");
        var failed = new TenantRoleService(authority, roles, event -> { throw new IllegalStateException("outbox unavailable"); },
                ids, java.time.Clock.systemUTC(), new ObjectMapper(), "saas.forge.dev.tenant-access-service.events");
        assertThatThrownBy(() -> transactions.execute(status -> failed.create(context, "ROLLED_BACK",
                java.util.List.of("example:project:list")))).hasMessageContaining("outbox unavailable");
        java.util.List<JdbcTenantRoleRepository.Role> remaining = transactions.execute(status -> roles.list(tenantId));
        assertThat(remaining).extracting(JdbcTenantRoleRepository.Role::roleKey)
                .contains("AUDITED_READER").doesNotContain("ROLLED_BACK");
    }

    @Test
    @Order(1)
    void releaseImportPreservesOmittedPermissionsAndRejectsSemanticReuse() throws Exception {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").target("22").load().migrate();
        try (var connection = DriverManager.getConnection(url(), "tenant_access_migrator", "tenant-access-migrator-password");
                var statement = connection.prepareStatement("SELECT import_permission_catalog(?::jsonb, ?, ?)")) {
            statement.setString(1, Files.readString(repositoryRoot().resolve(
                    "saas-forge-services/tenant-access-service/permission-catalog/v1.json")));
            statement.setString(2, "a".repeat(64));
            statement.setString(3, "saas.forge.dev.tenant-access-service.events");
            statement.execute();
        }
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(
                url(), "tenant_access_migrator", "tenant-access-migrator-password");
                var statement = connection.prepareStatement("SELECT import_permission_catalog(?::jsonb, ?, ?)")) {
            UUID tenantId;
            UUID membershipId;
            UUID identityId;
            UUID otherMembershipId;
            UUID otherIdentityId;
            try (var seed = connection.createStatement()) {
                try (var rows = seed.executeQuery("INSERT INTO tenants (display_name, tenant_status) VALUES ('Permission test', 'ACTIVE') RETURNING id")) {
                    rows.next();
                    tenantId = rows.getObject(1, UUID.class);
                }
                try (var rows = seed.executeQuery("SELECT uuidv7()")) { rows.next(); identityId = rows.getObject(1, UUID.class); }
                try (var rows = seed.executeQuery("SELECT uuidv7()")) { rows.next(); otherIdentityId = rows.getObject(1, UUID.class); }
                try (var rows = seed.executeQuery("INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES ('"
                        + tenantId + "', '" + identityId + "', 'ENABLED') RETURNING id")) {
                    rows.next(); membershipId = rows.getObject(1, UUID.class);
                }
                try (var rows = seed.executeQuery("INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES ('"
                        + tenantId + "', '" + otherIdentityId + "', 'ENABLED') RETURNING id")) {
                    rows.next(); otherMembershipId = rows.getObject(1, UUID.class);
                }
                UUID roleId;
                try (var rows = seed.executeQuery("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES (uuidv7(), '"
                        + tenantId + "', 'TENANT_ADMINISTRATOR', true, now()) RETURNING id")) {
                    rows.next(); roleId = rows.getObject(1, UUID.class);
                }
                seed.execute("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES ('"
                        + tenantId + "', '" + membershipId + "', '" + roleId + "', now())");
            }
            statement.setString(1, "{\"revision\":1,\"source\":\"example-service\",\"permissions\":[{\"code\":\"example:project:list\",\"semanticId\":\"listProjects\",\"enabled\":true}]}");
            statement.setString(2, "a".repeat(64));
            statement.setString(3, "saas.forge.dev.tenant-access-service.events");
            statement.execute();
            statement.setString(1, "{\"revision\":2,\"source\":\"example-service\",\"permissions\":[{\"code\":\"example:task:list\",\"semanticId\":\"listTasks\",\"enabled\":true}]}");
            statement.setString(2, "b".repeat(64));
            statement.execute();
            var dataSource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
            var authority = new JdbcPermissionAuthority(new JdbcTemplate(dataSource));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var admin = new TenantContextSnapshot(identityId, membershipId, tenantId);
            var ordinary = new TenantContextSnapshot(otherIdentityId, otherMembershipId, tenantId);
            assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(admin, "example:project:list")))).isTrue();
            assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(admin, "example:task:list")))).isTrue();
            assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(ordinary, "example:project:list")))).isFalse();
            assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(admin, "example:project:unknown")))).isFalse();
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT count(*) FROM permission_catalog WHERE enabled AND source = 'example-service'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = connection.createStatement().executeQuery("""
                    SELECT count(*), count(*) FILTER (WHERE event_snapshot->'data'->>'code' = 'example:project:list')
                    FROM tenant_access_outbox_events
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getInt(2)).isEqualTo(1);
            }
            statement.setString(1, "{\"revision\":3,\"source\":\"example-service\",\"permissions\":[{\"code\":\"example:project:list\",\"semanticId\":\"deleteProject\",\"enabled\":true}]}");
            statement.setString(2, "c".repeat(64));
            assertThatThrownBy(statement::execute).hasMessageContaining("semantic");
            statement.setString(1, "{\"revision\":3,\"source\":\"example-service\",\"permissions\":[{\"code\":\"example:project:list\",\"semanticId\":\"listProjects\",\"enabled\":false}]}");
            statement.execute();
            assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(admin, "example:project:list")))).isFalse();
            try (var rows = connection.createStatement().executeQuery("SELECT enabled FROM permission_catalog WHERE code = 'example:project:list'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isFalse();
            }
            statement.setString(1, "{\"revision\":4,\"source\":\"example-service\",\"permissions\":[{\"code\":\"example:project:list\",\"semanticId\":\"listProjects\",\"enabled\":true}]}");
            statement.setString(2, "d".repeat(64));
            statement.execute();
            assertThat(Boolean.TRUE.equals(transactions.execute(status -> authority.allowed(admin, "example:project:list")))).isTrue();
            try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM tenant_access_outbox_events")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
        }
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
