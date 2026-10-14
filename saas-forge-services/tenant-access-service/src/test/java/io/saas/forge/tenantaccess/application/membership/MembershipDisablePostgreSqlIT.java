package io.saas.forge.tenantaccess.application.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway.Result;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcPermissionAuthority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MembershipDisablePostgreSqlIT {
    private static final Path ROOT = repositoryRoot();
    private static final String TOPIC = "saas.forge.test.tenant-access-service.events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
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
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    private JdbcTemplate migrator;
    private JdbcTemplate app;
    private MembershipDisableService service;
    private UuidV7Generator ids;
    private final AtomicInteger releases = new AtomicInteger();
    private volatile boolean releaseFails;
    private volatile CountDownLatch revocationEntered;
    private volatile CountDownLatch finishRevocation;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(jdbcUrl(), "tenant_access_migrator",
                "tenant-access-migrator-password").locations("classpath:db/migration").load().migrate();
        migrator = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(),
                "tenant_access_migrator", "tenant-access-migrator-password"));
        migrator.execute("TRUNCATE tenant_access_outbox_events, membership_disable_workflows, memberships, tenants CASCADE");
        var dataSource = new DriverManagerDataSource(jdbcUrl(),
                "tenant_access_app", "tenant-access-app-password");
        app = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
        OutboxEventRepository outbox = event -> app.update("""
                INSERT INTO tenant_access_outbox_events
                  (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, event.eventId(), event.tenantId(),
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC), event.topic(),
                event.orderingKey(), event.traceId(), event.eventSnapshot(),
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
        MembershipRevocationGateway revocations = new MembershipRevocationGateway() {
            @Override public Result revoke(UUID requestId, UUID tenantId, UUID membershipId) {
                CountDownLatch entered = revocationEntered;
                if (entered != null) {
                    entered.countDown();
                    try {
                        if (!finishRevocation.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return Result.completed(2, 3);
            }
            @Override public void recover(UUID requestId, UUID tenantId, UUID membershipId) {}
        };
        MembershipQuotaGateway quota = (tenantId, operationId) -> {
            releases.incrementAndGet();
            if (releaseFails) throw new IllegalStateException("quota unavailable");
        };
        service = new MembershipDisableService(app, new DataSourceTransactionManager(dataSource),
                new JdbcPermissionAuthority(app), revocations, quota, outbox,
                new ObjectMapper(), ids, Clock.systemUTC(), TOPIC);
        releases.set(0);
        releaseFails = false;
        revocationEntered = null;
        finishRevocation = null;
    }

    @Test
    void disablesOnlyTargetAndRecoversCommittedQuotaReleaseWithoutRestoringRoles() {
        UUID tenant = ids.next();
        UUID admin = ids.next();
        UUID adminIdentity = ids.next();
        UUID member = ids.next();
        UUID memberIdentity = ids.next();
        UUID role = ids.next();
        seedTenant(tenant, role);
        seedMembership(tenant, admin, adminIdentity, role);
        seedMembership(tenant, member, memberIdentity, null);
        UUID assignedRole = ids.next();
        migrator.update("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES (?, ?, 'MEMBER', false, now())",
                assignedRole, tenant);
        migrator.update("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES (?, ?, ?, now())",
                tenant, member, assignedRole);
        TenantContextSnapshot actor = new TenantContextSnapshot(adminIdentity, admin, tenant);
        releaseFails = true;

        var pending = service.disable(actor, member, ids.next(), null);
        assertEquals("DISABLED", pending.membershipStatus());
        assertEquals("RELEASE_PENDING", pending.operationStatus());
        assertEquals("DISABLED", scalar("SELECT membership_status FROM memberships WHERE id = ?", member));
        assertEquals(1, count("SELECT count(*) FROM membership_role_assignments WHERE membership_id = ?", member));
        assertEquals(1, count("SELECT count(*) FROM tenant_access_outbox_events WHERE event_snapshot->>'type' = 'com.saas.forge.membership.disabled.v1'"));
        assertEquals(0, count("SELECT count(*) FROM validate_membership(?, ?)", memberIdentity, member));

        releaseFails = false;
        migrator.update("UPDATE membership_disable_workflows SET next_attempt_at = now() - interval '1 second' WHERE operation_id = ?",
                pending.operationId());
        service.recoverNext();
        assertEquals("COMPLETED", service.read(actor, member).operationStatus());
        assertEquals(2, releases.get());
        assertEquals(1, count("SELECT count(*) FROM tenant_access_outbox_events WHERE event_snapshot->>'type' = 'com.saas.forge.membership.disabled.v1'"));
        assertThrows(MembershipDisableFailure.class, () -> service.disable(actor, member, ids.next(), null));
    }

    @Test
    void concurrentAdministratorDisablesCannotRemoveBothUsableAdministrators() throws Exception {
        UUID tenant = ids.next();
        UUID first = ids.next();
        UUID firstIdentity = ids.next();
        UUID second = ids.next();
        UUID secondIdentity = ids.next();
        UUID role = ids.next();
        seedTenant(tenant, role);
        seedMembership(tenant, first, firstIdentity, role);
        seedMembership(tenant, second, secondIdentity, role);
        revocationEntered = new CountDownLatch(1);
        finishRevocation = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstCall = executor.submit(() -> service.disable(
                    new TenantContextSnapshot(firstIdentity, first, tenant), second, ids.next(), null));
            assertTrue(revocationEntered.await(10, TimeUnit.SECONDS));
            MembershipDisableFailure denied = assertThrows(MembershipDisableFailure.class,
                    () -> service.disable(new TenantContextSnapshot(secondIdentity, second, tenant),
                            first, ids.next(), null));
            assertTrue("LAST_TENANT_ADMINISTRATOR".equals(denied.code())
                    || "MEMBERSHIP_DISABLE_FORBIDDEN".equals(denied.code()));
            finishRevocation.countDown();
            firstCall.get(10, TimeUnit.SECONDS);
        } finally {
            finishRevocation.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, count("SELECT count(*) FROM memberships WHERE tenant_id = ? AND membership_status = 'ENABLED'", tenant));
    }

    private void seedTenant(UUID tenant, UUID adminRole) {
        migrator.update("INSERT INTO tenants (id, display_name, tenant_status) VALUES (?, 'Test', 'ACTIVE')", tenant);
        migrator.update("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES (?, ?, 'TENANT_ADMINISTRATOR', true, now())",
                adminRole, tenant);
    }

    private void seedMembership(UUID tenant, UUID membership, UUID identity, UUID role) {
        migrator.update("INSERT INTO memberships (id, tenant_id, identity_id, membership_status) VALUES (?, ?, ?, 'ENABLED')",
                membership, tenant, identity);
        if (role != null) migrator.update("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES (?, ?, ?, now())",
                tenant, membership, role);
    }

    private String scalar(String sql, Object... args) { return migrator.queryForObject(sql, String.class, args); }
    private int count(String sql, Object... args) { return migrator.queryForObject(sql, Integer.class, args); }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root unavailable");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/tenant_access_db";
    }
}
