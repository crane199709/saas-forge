package io.saas.forge.tenantaccess.application.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
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
import java.util.HashSet;
import java.util.Set;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MembershipEnablePostgreSqlIT {
    private static final Path ROOT = repositoryRoot();
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
    private UuidV7Generator ids;
    private MembershipEnableService service;
    private TransactionTemplate transactions;
    private DataSourceTransactionManager manager;
    private MembershipEnableQuotaGateway quotaGateway;
    private MembershipEnableFenceGateway fenceGateway;
    private OutboxEventRepository outboxRepository;
    private final AtomicInteger consumes = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicInteger fenceReleases = new AtomicInteger();
    private final Set<UUID> consumedOperations = new HashSet<>();
    private final Set<UUID> releasedRevocations = new HashSet<>();
    private volatile boolean quotaExhausted;
    private volatile boolean consumeResponseLost;
    private volatile boolean outboxFailure;
    private volatile boolean commitResponseLost;
    private volatile boolean fenceUnavailable;
    private volatile boolean releaseUnavailable;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure().dataSource(jdbcUrl(), "tenant_access_migrator",
                "tenant-access-migrator-password").locations("classpath:db/migration").load().migrate();
        migrator = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(),
                "tenant_access_migrator", "tenant-access-migrator-password"));
        migrator.execute("TRUNCATE tenant_access_outbox_events, membership_enable_workflows, membership_disable_workflows, memberships, tenants CASCADE");
        migrator.queryForObject("SELECT import_permission_catalog(CAST(? AS jsonb), ?, ?)", Integer.class,
                Files.readString(ROOT.resolve("saas-forge-services/tenant-access-service/permission-catalog/v3.json")),
                "b".repeat(64), "saas.forge.test.tenant-access-service.events");
        var dataSource = new DriverManagerDataSource(jdbcUrl(),
                "tenant_access_app", "tenant-access-app-password");
        app = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        ids = new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
        consumes.set(0); releases.set(0); fenceReleases.set(0);
        consumedOperations.clear();
        releasedRevocations.clear();
        quotaExhausted = false; consumeResponseLost = false; outboxFailure = false;
        commitResponseLost = false;
        fenceUnavailable = false; releaseUnavailable = false;
        outboxRepository = event -> {
            if (outboxFailure && event.eventSnapshot().contains("membership.enabled.v1"))
                throw new IllegalStateException("injected local outbox failure");
            app.update("""
                INSERT INTO tenant_access_outbox_events
                  (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, event.eventId(), event.tenantId(),
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC), event.topic(),
                event.orderingKey(), event.traceId(), event.eventSnapshot(),
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
            if (commitResponseLost && event.eventSnapshot().contains("membership.enabled.v1")) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { throw new IllegalStateException("commit response lost"); }
                });
            }
        };
        quotaGateway = new MembershipEnableQuotaGateway() {
                    @Override public void consume(UUID tenantId, UUID operationId) {
                        if (quotaExhausted) throw new MembershipQuotaExceededException(new IllegalStateException());
                        if (consumedOperations.add(operationId)) consumes.incrementAndGet();
                        if (consumeResponseLost) throw new IllegalStateException("quota response lost");
                    }
                    @Override public void release(UUID tenantId, UUID operationId) {
                        if (releaseUnavailable) throw new IllegalStateException("quota release unavailable");
                        releases.incrementAndGet();
                    }
                };
        fenceGateway = (releaseId, revocationId, tenantId, membershipId) -> {
                    fenceReleases.incrementAndGet();
                    if (fenceUnavailable) throw new IllegalStateException("IAM unavailable");
                    releasedRevocations.add(revocationId);
                };
        restartService();
    }

    @Test
    void completedDisableCanBeEnabledWithOneNewSeatAndOldRevocationGeneration() {
        Fixture fixture = disabledMember("COMPLETED");
        UUID tenant = fixture.tenant();
        UUID member = fixture.member();
        UUID memberIdentity = fixture.memberIdentity();
        TenantContextSnapshot actor = fixture.actor();
        UUID operationId = ids.next();
        UUID memberRole = ids.next();
        UUID unit = ids.next();
        migrator.update("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES (?, ?, 'MEMBER', false, now())",
                memberRole, tenant);
        migrator.update("INSERT INTO tenant_role_permissions (tenant_id, role_id, permission_code, assigned_at) VALUES (?, ?, 'tenant-access:membership:read', now())",
                tenant, memberRole);
        migrator.update("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES (?, ?, ?, now())",
                tenant, member, memberRole);
        migrator.update("INSERT INTO organization_units (id, tenant_id, name) VALUES (?, ?, 'Team')", unit, tenant);
        migrator.update("INSERT INTO organization_unit_memberships (tenant_id, unit_id, membership_id) VALUES (?, ?, ?)",
                tenant, unit, member);

        var result = service.enable(actor, member, operationId, null);

        assertEquals("COMPLETED", result.operationStatus());
        assertEquals("ENABLED", result.membershipStatus());
        assertEquals(1, consumes.get());
        assertEquals(0, releases.get());
        assertEquals(1, fenceReleases.get());
        assertTrue(releasedRevocations.contains(fixture.revocationId()));
        assertEquals(1, migrator.queryForObject("SELECT count(*) FROM validate_membership(?, ?)", Integer.class, memberIdentity, member));
        assertEquals(1, migrator.queryForObject("SELECT count(*) FROM membership_role_assignments WHERE membership_id = ? AND role_id = ?",
                Integer.class, member, memberRole));
        assertEquals(1, migrator.queryForObject("SELECT count(*) FROM organization_unit_memberships WHERE membership_id = ? AND unit_id = ?",
                Integer.class, member, unit));
        assertTrue(Boolean.TRUE.equals(transactions.execute(status -> new JdbcPermissionAuthority(app).allowed(
                new TenantContextSnapshot(memberIdentity, member, tenant), "tenant-access:membership:read"))));
        assertEquals(1, migrator.queryForObject("SELECT count(*) FROM tenant_access_outbox_events WHERE event_snapshot->>'type' = 'com.saas.forge.membership.enabled.v1'", Integer.class));
        assertEquals("COMPLETED", service.enable(actor, member, operationId, null).operationStatus());
        assertEquals(1, consumes.get());
        assertEquals(1, fenceReleases.get());
    }

    @Test
    void refusesEnableUntilDisableAndQuotaReleaseAreCompleted() {
        Fixture fixture = disabledMember("RELEASE_PENDING");
        MembershipEnableFailure rejected = assertThrows(MembershipEnableFailure.class,
                () -> service.enable(fixture.actor(), fixture.member(), ids.next(), null));
        assertEquals("MEMBERSHIP_DISABLE_IN_PROGRESS", rejected.code());
        assertEquals(0, consumes.get());
    }

    @Test
    void quotaExhaustionKeepsMemberDisabledAndAllowsLaterIndependentAttempt() {
        Fixture fixture = disabledMember("COMPLETED");
        quotaExhausted = true;
        UUID firstKey = ids.next();
        assertEquals("QUOTA_REJECTED", service.enable(fixture.actor(), fixture.member(), firstKey, null).operationStatus());
        assertEquals("QUOTA_REJECTED", service.enable(fixture.actor(), fixture.member(), firstKey, null).operationStatus());
        assertEquals("DISABLED", status(fixture.member()));
        assertEquals(0, consumes.get());
        quotaExhausted = false;
        assertEquals("COMPLETED", service.enable(fixture.actor(), fixture.member(), ids.next(), null).operationStatus());
        assertEquals(1, consumes.get());
    }

    @Test
    void lostConsumeResponseReusesQuotaOperationAndDoesNotDoubleCount() {
        Fixture fixture = disabledMember("COMPLETED");
        consumeResponseLost = true;
        UUID key = ids.next();
        assertEquals("CONSUMING", service.enable(fixture.actor(), fixture.member(), key, null).operationStatus());
        assertEquals("DISABLED", status(fixture.member()));
        consumeResponseLost = false;
        assertEquals("COMPLETED", service.enable(fixture.actor(), fixture.member(), key, null).operationStatus());
        assertEquals(1, consumes.get());
        assertEquals(0, releases.get());
    }

    @Test
    void localFailureAfterConsumePersistsCompensationAndNoEnabledFact() {
        Fixture fixture = disabledMember("COMPLETED");
        outboxFailure = true;
        var progress = service.enable(fixture.actor(), fixture.member(), ids.next(), null);
        assertEquals("COMPENSATED", progress.operationStatus());
        assertEquals("DISABLED", status(fixture.member()));
        assertEquals(1, consumes.get());
        assertEquals(1, releases.get());
        assertEquals(0, migrator.queryForObject("SELECT count(*) FROM tenant_access_outbox_events WHERE event_snapshot->>'type' = 'com.saas.forge.membership.enabled.v1'", Integer.class));
        assertEquals(0, migrator.queryForObject("SELECT count(*) FROM validate_membership(?, ?)", Integer.class,
                fixture.memberIdentity(), fixture.member()));
    }

    @Test
    void committedEnableWithLostResponseMustNotReleaseItsConsumedSeat() {
        Fixture fixture = disabledMember("COMPLETED");
        commitResponseLost = true;
        var progress = service.enable(fixture.actor(), fixture.member(), ids.next(), null);
        assertEquals("COMPLETED", progress.operationStatus());
        assertEquals("ENABLED", status(fixture.member()));
        assertEquals(1, consumes.get());
        assertEquals(0, releases.get());
        assertEquals(1, fenceReleases.get());
    }

    @Test
    void aNewWorkerCompletesDurableCompensationAfterReleaseDependencyRecovers() {
        Fixture fixture = disabledMember("COMPLETED");
        outboxFailure = true;
        releaseUnavailable = true;
        var pending = service.enable(fixture.actor(), fixture.member(), ids.next(), null);
        assertEquals("COMPENSATION_PENDING", pending.operationStatus());
        assertEquals("DISABLED", status(fixture.member()));
        releaseUnavailable = false;
        migrator.update("UPDATE membership_enable_workflows SET next_attempt_at = now() - interval '1 second' WHERE operation_id = ?",
                pending.operationId());
        restartService();
        service.recoverNext();
        assertEquals("COMPENSATED", service.read(fixture.actor(), fixture.member()).operationStatus());
        assertEquals("DISABLED", status(fixture.member()));
        assertEquals(1, consumes.get());
        assertEquals(1, releases.get());
    }

    @Test
    void pendingFenceReleaseKeepsContextBlockedUntilRecovered() {
        Fixture fixture = disabledMember("COMPLETED");
        fenceUnavailable = true;
        UUID key = ids.next();
        var pending = service.enable(fixture.actor(), fixture.member(), key, null);
        assertEquals("FENCE_PENDING", pending.operationStatus());
        assertTrue(pending.pending());
        assertEquals("ENABLED", status(fixture.member()));
        assertEquals(0, migrator.queryForObject("SELECT count(*) FROM validate_membership(?, ?)", Integer.class,
                fixture.memberIdentity(), fixture.member()));
        fenceUnavailable = false;
        assertEquals("COMPLETED", service.enable(fixture.actor(), fixture.member(), key, null).operationStatus());
        assertEquals(1, consumes.get());
        assertEquals(1, migrator.queryForObject("SELECT count(*) FROM validate_membership(?, ?)", Integer.class,
                fixture.memberIdentity(), fixture.member()));
    }

    @Test
    void aSecondDisableGenerationUsesItsOwnFenceAndNewQuotaOperation() {
        Fixture fixture = disabledMember("COMPLETED");
        assertEquals("COMPLETED", service.enable(fixture.actor(), fixture.member(), ids.next(), null).operationStatus());
        UUID nextRevocation = ids.next();
        migrator.update("UPDATE memberships SET membership_status = 'DISABLED' WHERE id = ?", fixture.member());
        migrator.update("""
                INSERT INTO membership_disable_workflows
                (operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
                 revocation_request_id, release_operation_id, workflow_status, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', now(), now() + interval '1 second', now())
                """, ids.next(), fixture.tenant(), fixture.member(), fixture.actor().membershipId(),
                fixture.actor().identityId(), nextRevocation, ids.next());

        assertEquals("COMPLETED", service.enable(fixture.actor(), fixture.member(), ids.next(), null).operationStatus());
        assertEquals(2, consumes.get());
        assertEquals(Set.of(fixture.revocationId(), nextRevocation), releasedRevocations);
    }

    private Fixture disabledMember(String disableStatus) {
        UUID tenant = ids.next();
        UUID admin = ids.next();
        UUID adminIdentity = ids.next();
        UUID member = ids.next();
        UUID memberIdentity = ids.next();
        UUID adminRole = ids.next();
        UUID disableId = ids.next();
        UUID revocationId = ids.next();
        migrator.update("INSERT INTO tenants (id, display_name, tenant_status) VALUES (?, 'Test', 'ACTIVE')", tenant);
        migrator.update("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES (?, ?, 'TENANT_ADMINISTRATOR', true, now())", adminRole, tenant);
        migrator.update("INSERT INTO memberships (id, tenant_id, identity_id, membership_status) VALUES (?, ?, ?, 'ENABLED')", admin, tenant, adminIdentity);
        migrator.update("INSERT INTO memberships (id, tenant_id, identity_id, membership_status) VALUES (?, ?, ?, 'DISABLED')", member, tenant, memberIdentity);
        migrator.update("INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at) VALUES (?, ?, ?, now())", tenant, admin, adminRole);
        migrator.update("""
                INSERT INTO membership_disable_workflows
                (operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
                 revocation_request_id, release_operation_id, workflow_status, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), now())
                """, disableId, tenant, member, admin, adminIdentity, revocationId, ids.next(), disableStatus);
        return new Fixture(tenant, member, memberIdentity, revocationId,
                new TenantContextSnapshot(adminIdentity, admin, tenant));
    }

    private String status(UUID membershipId) {
        return migrator.queryForObject("SELECT membership_status FROM memberships WHERE id = ?", String.class, membershipId);
    }

    private void restartService() {
        service = new MembershipEnableService(app, manager, new JdbcPermissionAuthority(app),
                quotaGateway, fenceGateway, outboxRepository, new ObjectMapper(), ids,
                Clock.systemUTC(), "saas.forge.test.tenant-access-service.events");
    }

    private record Fixture(UUID tenant, UUID member, UUID memberIdentity, UUID revocationId,
            TenantContextSnapshot actor) {}

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
