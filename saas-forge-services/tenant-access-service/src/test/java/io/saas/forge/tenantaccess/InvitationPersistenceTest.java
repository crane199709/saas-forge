package io.saas.forge.tenantaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saas.forge.tenantaccess.application.invitation.InvitationEventFactory;
import io.saas.forge.tenantaccess.application.invitation.InvitationFailure;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
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
        assertEquals("EXPIRED", later.list(context).get(0).status());
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
