package io.saas.forge.tenantaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saas.forge.tenantaccess.application.administrator.QuotaUnavailableException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.invitation.InvitationEventFactory;
import io.saas.forge.tenantaccess.application.invitation.InvitationFailure;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceService;
import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupService;
import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupGateway;
import io.saas.forge.tenantaccess.application.invitation.InvitationQuotaGateway;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcInvitationAcceptanceRepository;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcInvitationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
class InvitationPersistenceTest {
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
    void createResendRevokePreserveExpiryAndCommittedFactsUnderRls() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenantId = migrator.queryForObject("""
                INSERT INTO tenants (display_name, tenant_status) VALUES ('Invitation test', 'ACTIVE') RETURNING id
                """, UUID.class);
        UUID actor = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID memberId = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenantId, actor);
        UUID invitee = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        TenantContextSnapshot context = new TenantContextSnapshot(actor, memberId, tenantId);
        var datasource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        var repository = new JdbcInvitationRepository(jdbc);
        Instant at = Instant.parse("2026-10-14T10:00:00Z");
        var ids = new UuidV7Generator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom());
        List<String> delivered = new ArrayList<>();
        IdentityProvisioningGateway identities = (requestId, email, displayName) ->
                new IdentityProvisioningGateway.Result(invitee, IdentityCredentialDisposition.SETUP_ALLOWED);
        var outbox = (io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository) event -> jdbc.update("""
                INSERT INTO tenant_access_outbox_events
                    (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, event.eventId(), event.tenantId(), Timestamp.from(event.occurredAt()), event.topic(),
                event.orderingKey(), event.traceId(), event.eventSnapshot(), Timestamp.from(event.occurredAt()));
        var events = new InvitationEventFactory(new ObjectMapper(), ids,
                "saas.forge.test.tenant-access-service.events");
        InvitationService service = new InvitationService(repository, identities, outbox, events,
                (email, token) -> delivered.add(token), transactions, ids, new SecureRandom(),
                Clock.fixed(at, ZoneOffset.UTC));

        var created = service.create(context, " Member@Example.com ", null);
        assertEquals("member@example.com", created.email());
        assertEquals(at.plusSeconds(7 * 86400), created.expiresAt());
        assertEquals(1, delivered.size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitations", Integer.class));
        assertEquals("INVITATION_ALREADY_PENDING", assertThrows(InvitationFailure.class,
                () -> service.create(context, "member@example.com", null)).code());

        var resent = service.resend(context, created.id(), null);
        assertEquals(created.expiresAt(), resent.expiresAt());
        assertNotEquals(delivered.get(0), delivered.get(1));
        String storedDigest = transactions.execute(status -> {
            repository.setTarget(tenantId);
            return repository.lock(tenantId, created.id()).tokenHash();
        });
        assertNotEquals(digest(delivered.get(0)), storedDigest);
        assertEquals(digest(delivered.get(1)), storedDigest);
        service.revoke(context, created.id(), null);
        assertThrows(RuntimeException.class, () -> service.resend(context, created.id(), null));
        assertEquals("REVOKED", service.list(context).get(0).status());
        var replacement = service.create(context, "member@example.com", null);
        var later = new InvitationService(repository, identities, outbox, events,
                (email, token) -> delivered.add(token), transactions, ids, new SecureRandom(),
                Clock.fixed(at.plusSeconds(7 * 86400), ZoneOffset.UTC));
        assertEquals("EXPIRED", later.list(context).stream()
                .filter(item -> item.id().equals(replacement.id())).findFirst().orElseThrow().status());
        assertThrows(RuntimeException.class, () -> later.resend(context, replacement.id(), null));
        migrator.update("INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES (?, ?, 'DISABLED')",
                tenantId, invitee);
        assertEquals("MEMBERSHIP_ALREADY_EXISTS", assertThrows(InvitationFailure.class,
                () -> service.create(context, "other@example.com", null)).code());
        assertEquals(Integer.valueOf(4), transactions.execute(status -> {
            repository.setTarget(tenantId);
            return jdbc.queryForObject("SELECT count(*) FROM tenant_access_outbox_events", Integer.class);
        }));
        assertTrue(delivered.stream().noneMatch(String::isBlank));
    }

    @Test
    void authenticatedInviteeAcceptsOnceWithoutRoleAndReplayDoesNotConsumeAgain() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenantId = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Members', 'ACTIVE') RETURNING id",
                UUID.class);
        UUID actor = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID actorMembership = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenantId, actor);
        UUID invitee = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        var datasource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        Instant at = Instant.parse("2026-10-14T10:00:00Z");
        var ids = new UuidV7Generator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom());
        List<String> delivered = new ArrayList<>();
        var outbox = (io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository) event -> jdbc.update("""
                INSERT INTO tenant_access_outbox_events
                    (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, event.eventId(), event.tenantId(), Timestamp.from(event.occurredAt()), event.topic(),
                event.orderingKey(), event.traceId(), event.eventSnapshot(), Timestamp.from(event.occurredAt()));
        var events = new InvitationEventFactory(new ObjectMapper(), ids, "saas.forge.test.events");
        var invitations = new JdbcInvitationRepository(jdbc);
        IdentityProvisioningGateway identities = (requestId, email, displayName) ->
                new IdentityProvisioningGateway.Result(invitee, IdentityCredentialDisposition.PASSWORD_READY);
        var creator = new InvitationService(invitations, identities, outbox, events,
                (email, token) -> delivered.add(token), transactions, ids, new SecureRandom(),
                Clock.fixed(at, ZoneOffset.UTC));
        var invitation = creator.create(new TenantContextSnapshot(actor, actorMembership, tenantId),
                "member@example.com", null);
        List<UUID> consumed = new ArrayList<>();
        AtomicBoolean quotaExceeded = new AtomicBoolean(true);
        InvitationQuotaGateway quota = new InvitationQuotaGateway() {
            @Override public void consume(UUID target, UUID operationId) {
                if (quotaExceeded.getAndSet(false))
                    throw new QuotaUnavailableException("QUOTA_EXCEEDED", null);
                consumed.add(operationId);
            }
            @Override public void release(UUID target, UUID operationId) { throw new AssertionError(); }
        };
        var acceptor = new InvitationAcceptanceService(new JdbcInvitationAcceptanceRepository(jdbc), quota,
                outbox, events, transactions, ids, Clock.fixed(at, ZoneOffset.UTC));
        UUID key = ids.next();
        assertEquals("INVITATION_IDENTITY_MISMATCH", assertThrows(InvitationFailure.class,
                () -> acceptor.accept(delivered.get(0), actor, key, null)).code());
        assertEquals("QUOTA_EXCEEDED", assertThrows(InvitationFailure.class,
                () -> acceptor.accept(delivered.get(0), invitee, key, null)).code());
        assertEquals("INVITATION_ACCEPTANCE_RETRY_REQUIRED", assertThrows(InvitationFailure.class,
                () -> acceptor.accept(delivered.get(0), invitee, key, null)).code());
        UUID retryKey = ids.next();
        var result = acceptor.accept(delivered.get(0), invitee, retryKey, null);
        assertEquals(tenantId, result.tenantId());
        assertEquals(false, result.replayed());
        assertEquals(true, acceptor.accept(delivered.get(0), invitee, retryKey, null).replayed());
        assertEquals(1, consumed.size());
        assertEquals("ENABLED", transactions.execute(status -> {
            invitations.setTarget(tenantId);
            return jdbc.queryForObject("SELECT membership_status FROM memberships WHERE id = ?",
                    String.class, result.membershipId());
        }));
        assertEquals(Integer.valueOf(0), transactions.execute(status -> {
            invitations.setTarget(tenantId);
            return jdbc.queryForObject("SELECT count(*) FROM membership_role_assignments WHERE membership_id = ?",
                    Integer.class, result.membershipId());
        }));
        assertEquals("ACCEPTED", creator.list(new TenantContextSnapshot(actor, actorMembership, tenantId))
                .stream().filter(item -> item.id().equals(invitation.id())).findFirst().orElseThrow().status());
    }

    @Test
    void onlyCurrentPendingInvitationCanRequestFirstPasswordSetupWithoutUsingQuota() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenantId = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Setup', 'ACTIVE') RETURNING id", UUID.class);
        UUID actor = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID actorMembership = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenantId, actor);
        UUID invitee = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        var datasource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        Instant at = Instant.parse("2026-10-14T10:00:00Z");
        var ids = new UuidV7Generator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom());
        List<String> delivered = new ArrayList<>();
        IdentityProvisioningGateway identities = (requestId, email, displayName) ->
                new IdentityProvisioningGateway.Result(invitee, IdentityCredentialDisposition.SETUP_ALLOWED);
        var outbox = (io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository) event -> {};
        var invitations = new JdbcInvitationRepository(jdbc);
        var creator = new InvitationService(invitations, identities, outbox,
                new InvitationEventFactory(new ObjectMapper(), ids, "saas.forge.test.events"),
                (email, token) -> delivered.add(token), transactions, ids, new SecureRandom(),
                Clock.fixed(at, ZoneOffset.UTC));
        var invitation = creator.create(new TenantContextSnapshot(actor, actorMembership, tenantId),
                "new@example.com", null);
        List<UUID> setupRequests = new ArrayList<>();
        var setups = new InvitationPasswordSetupService(new JdbcInvitationAcceptanceRepository(jdbc),
                (requestId, identityId) -> {
                    assertEquals(invitee, identityId);
                    setupRequests.add(requestId);
                    return InvitationPasswordSetupGateway.Result.MAIL_SERVICE_ACCEPTED;
                }, transactions, Clock.fixed(at, ZoneOffset.UTC));
        UUID key = ids.next();
        assertEquals("INVITATION_INVALID", assertThrows(InvitationFailure.class,
                () -> setups.request("not-a-token", key)).code());
        assertEquals(InvitationPasswordSetupGateway.Result.MAIL_SERVICE_ACCEPTED,
                setups.request(delivered.get(0), key));
        assertEquals(List.of(key), setupRequests);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitations", Integer.class));
        creator.resend(new TenantContextSnapshot(actor, actorMembership, tenantId), invitation.id(), null);
        assertEquals("INVITATION_INVALID", assertThrows(InvitationFailure.class,
                () -> setups.request(delivered.get(0), ids.next())).code());
        creator.revoke(new TenantContextSnapshot(actor, actorMembership, tenantId), invitation.id(), null);
        assertEquals("INVITATION_REVOKED", assertThrows(InvitationFailure.class,
                () -> setups.request(delivered.get(1), ids.next())).code());
        assertEquals(List.of(key), setupRequests);
    }

    @Test
    void revokedInvitationKeepsPendingQuotaCompensationVisible() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Compensation', 'ACTIVE') RETURNING id",
                UUID.class);
        UUID actor = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID actorMembership = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenant, actor);
        UUID invitee = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        var datasource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        Instant at = Instant.parse("2026-10-14T10:00:00Z");
        var ids = new UuidV7Generator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom());
        var events = new InvitationEventFactory(new ObjectMapper(), ids, "saas.forge.test.events");
        var repo = new JdbcInvitationRepository(jdbc);
        List<String> delivered = new ArrayList<>();
        IdentityProvisioningGateway identities = (requestId, email, displayName) ->
                new IdentityProvisioningGateway.Result(invitee, IdentityCredentialDisposition.PASSWORD_READY);
        var outbox = (io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository) event -> {
            if (event.eventSnapshot().contains("invitation.accepted.v1"))
                throw new IllegalStateException("accepted fact write unavailable");
        };
        var creator = new InvitationService(repo, identities, outbox, events,
                (email, token) -> delivered.add(token), transactions, ids, new SecureRandom(),
                Clock.fixed(at, ZoneOffset.UTC));
        var invitation = creator.create(new TenantContextSnapshot(actor, actorMembership, tenant),
                "compensation@example.test", null);
        List<UUID> consumes = new ArrayList<>();
        List<UUID> releases = new ArrayList<>();
        AtomicBoolean releaseUnavailable = new AtomicBoolean(true);
        InvitationQuotaGateway quota = new InvitationQuotaGateway() {
            @Override public void consume(UUID target, UUID operationId) { consumes.add(operationId); }
            @Override public void release(UUID target, UUID operationId) {
                if (releaseUnavailable.get()) throw new RemoteWorkflowUnavailableException(new IllegalStateException());
                releases.add(operationId);
            }
        };
        var acceptor = new InvitationAcceptanceService(new JdbcInvitationAcceptanceRepository(jdbc), quota,
                outbox, events, transactions, ids, Clock.fixed(at, ZoneOffset.UTC));
        UUID key = ids.next();
        assertEquals("INVITATION_ACTIVATION_COMPENSATING", assertThrows(InvitationFailure.class,
                () -> acceptor.accept(delivered.get(0), invitee, key, null)).code());
        assertEquals(1, consumes.size());
        assertTrue(releases.isEmpty());
        releaseUnavailable.set(false);
        var firstRecovery = new InvitationAcceptanceService(new JdbcInvitationAcceptanceRepository(jdbc), quota,
                outbox, events, transactions, ids,
                Clock.fixed(at.plusSeconds(31), ZoneOffset.UTC));
        assertTrue(firstRecovery.recoverNext());
        assertEquals(1, releases.size());
        assertEquals("INVITATION_ACCEPTANCE_RETRY_REQUIRED", assertThrows(InvitationFailure.class,
                () -> firstRecovery.accept(delivered.get(0), invitee, ids.next(), null)).code());
        assertEquals(2, consumes.size());
        assertEquals(2, releases.size());
        assertNotEquals(consumes.get(0), consumes.get(1));
        assertNotEquals(releases.get(0), releases.get(1));
        releaseUnavailable.set(true);
        assertEquals("INVITATION_ACTIVATION_COMPENSATING", assertThrows(InvitationFailure.class,
                () -> firstRecovery.accept(delivered.get(0), invitee, ids.next(), null)).code());
        creator.revoke(new TenantContextSnapshot(actor, actorMembership, tenant), invitation.id(), null);
        assertEquals("INVITATION_ACTIVATION_COMPENSATING", assertThrows(InvitationFailure.class,
                () -> firstRecovery.accept(delivered.get(0), invitee, ids.next(), null)).code());
        releaseUnavailable.set(false);
        var recovered = new InvitationAcceptanceService(new JdbcInvitationAcceptanceRepository(jdbc), quota,
                outbox, events, transactions, ids,
                Clock.fixed(at.plusSeconds(8 * 86400), ZoneOffset.UTC));
        assertTrue(recovered.recoverNext());
        assertEquals(3, releases.size());
        assertEquals("COMPENSATED", migrator.queryForObject(
                "SELECT state FROM invitation_acceptance_attempts WHERE invitation_id = ?", String.class,
                invitation.id()));
        assertEquals(0, migrator.queryForObject(
                "SELECT count(*) FROM memberships WHERE tenant_id = ? AND identity_id = ?", Integer.class,
                tenant, invitee));
    }

    @Test
    void lostQuotaConsumeResponseRetriesSameAttemptWithoutDoubleCountingOrPrematureAcceptance() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Lost consume response', 'ACTIVE') RETURNING id",
                UUID.class);
        UUID administrator = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID administratorMembership = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenant, administrator);
        UUID invitee = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        var datasource = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        Instant at = Instant.parse("2026-10-14T10:00:00Z");
        var ids = new UuidV7Generator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom());
        var events = new InvitationEventFactory(new ObjectMapper(), ids, "saas.forge.test.events");
        List<String> links = new ArrayList<>();
        var outbox = (io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository) event -> jdbc.update("""
                INSERT INTO tenant_access_outbox_events
                    (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, event.eventId(), event.tenantId(), Timestamp.from(event.occurredAt()), event.topic(),
                event.orderingKey(), event.traceId(), event.eventSnapshot(), Timestamp.from(event.occurredAt()));
        var creator = new InvitationService(new JdbcInvitationRepository(jdbc),
                (requestId, email, displayName) -> new IdentityProvisioningGateway.Result(
                        invitee, IdentityCredentialDisposition.PASSWORD_READY),
                outbox, events, (email, token) -> links.add(token), transactions, ids,
                new SecureRandom(), Clock.fixed(at, ZoneOffset.UTC));
        var invitation = creator.create(new TenantContextSnapshot(
                administrator, administratorMembership, tenant), "lost@example.test", null);
        List<UUID> attempted = new ArrayList<>();
        AtomicBoolean responseLost = new AtomicBoolean(true);
        InvitationQuotaGateway quota = new InvitationQuotaGateway() {
            @Override public void consume(UUID target, UUID operationId) {
                attempted.add(operationId);
                if (responseLost.get())
                    throw new RemoteWorkflowUnavailableException(new IllegalStateException("response lost"));
            }
            @Override public void release(UUID target, UUID operationId) {
                throw new AssertionError("Uncertain consume must not be released");
            }
        };
        var acceptor = new InvitationAcceptanceService(new JdbcInvitationAcceptanceRepository(jdbc),
                quota, outbox, events, transactions, ids, Clock.fixed(at, ZoneOffset.UTC));
        UUID originalKey = ids.next();

        assertEquals("INVITATION_DEPENDENCY_UNAVAILABLE", assertThrows(InvitationFailure.class,
                () -> acceptor.accept(links.get(0), invitee, originalKey, null)).code());
        assertEquals("PENDING", creator.list(new TenantContextSnapshot(
                administrator, administratorMembership, tenant)).stream()
                .filter(item -> item.id().equals(invitation.id())).findFirst().orElseThrow().status());
        assertEquals(0, migrator.queryForObject(
                "SELECT count(*) FROM memberships WHERE tenant_id = ? AND identity_id = ?", Integer.class,
                tenant, invitee));
        responseLost.set(false);
        var accepted = acceptor.accept(links.get(0), invitee, ids.next(), null);
        assertEquals(false, accepted.replayed());
        assertEquals(true, acceptor.accept(links.get(0), invitee, originalKey, null).replayed());
        assertEquals(2, attempted.size());
        assertEquals(attempted.get(0), attempted.get(1));
        assertEquals(1, migrator.queryForObject(
                "SELECT count(*) FROM memberships WHERE tenant_id = ? AND identity_id = ?", Integer.class,
                tenant, invitee));
        assertEquals(1, migrator.queryForObject("""
                SELECT count(*) FROM tenant_access_outbox_events
                WHERE event_snapshot->>'type' = 'com.saas.forge.invitation.accepted.v1'
                AND tenant_id = ?
                """, Integer.class, tenant));
    }

    private static String url() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/tenant_access_db";
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
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
