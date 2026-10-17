package io.saas.forge.iam.application.authorization;

import io.saas.forge.iam.domain.authorization.PlatformRoleAssignment;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityCredentialStatus;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.NormalizedEmail;
import io.saas.forge.iam.application.authorization.PlatformAdminGrantResult.SetupState;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 授予请求、Identity、固定 Role Assignment 与 Outbox 共享一个本地事务。 */
public class PlatformAdminGrantTransaction {
    private static final String ROLE = "PLATFORM_ADMIN";
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository roles;
    private final OutboxEventRepository outbox;
    private final PlatformAdminGrantedEventFactory events;
    private final PlatformAdminSetupCaller setupCaller;
    private final Clock clock;

    public PlatformAdminGrantTransaction(JdbcTemplate jdbc, IdentityRepository identities,
            PlatformRoleAssignmentRepository roles, OutboxEventRepository outbox,
            PlatformAdminGrantedEventFactory events, PlatformAdminSetupCaller setupCaller, Clock clock) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.roles = roles;
        this.outbox = outbox;
        this.events = events;
        this.setupCaller = setupCaller;
        this.clock = clock;
    }

    @Transactional
    public PlatformAdminGrant grant(UUID actor, UUID requestId, String rawEmail, String traceId) {
        requireV7(actor, "actor");
        requireV7(requestId, "requestId");
        String email = NormalizedEmail.from(rawEmail).value();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("""
                INSERT INTO iam_platform_admin_grant_requests (request_id, actor_identity_id, email, created_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING
                """, requestId, actor, email, Timestamp.from(now));
        var request = jdbc.queryForObject("""
                SELECT actor_identity_id, email, identity_id, role_assignment_id, credential_status,
                       newly_granted, setup_caller_client_id, setup_state
                FROM iam_platform_admin_grant_requests WHERE request_id = ? FOR UPDATE
                """, (rs, row) -> new GrantRequest(
                rs.getObject("actor_identity_id", UUID.class), rs.getString("email"),
                rs.getObject("identity_id", UUID.class), rs.getObject("role_assignment_id", UUID.class),
                rs.getString("credential_status"), rs.getObject("newly_granted", Boolean.class),
                rs.getObject("setup_caller_client_id", UUID.class), rs.getString("setup_state")), requestId);
        if (request == null || !request.actor().equals(actor) || !request.email().equals(email)) {
            throw new IllegalStateException("PLATFORM_ADMIN_GRANT_REQUEST_CONFLICT");
        }
        if (request.identity() != null) {
            return new PlatformAdminGrant(request.identity(), request.assignment(),
                    IdentityCredentialStatus.valueOf(request.credentialStatus()), request.newlyGranted(),
                    request.setupCaller(), request.setupState() == null ? null : SetupState.valueOf(request.setupState()));
        }

        Identity identity = identities.findOrCreate(Identity.register(email, null, now));
        identities.lockIdentity(identity.id());
        IdentityCredentialStatus status = credentialStatus(identity.id(), now);
        var existing = roles.findActive(identity.id(), ROLE, now);
        boolean newlyGranted = existing.isEmpty();
        PlatformRoleAssignment assignment = existing.orElseGet(() ->
                roles.grant(PlatformRoleAssignment.grant(identity.id(), ROLE, now)));
        UUID callerId = newlyGranted && status == IdentityCredentialStatus.SETUP_ALLOWED
                ? setupCaller.callerClientId() : null;
        jdbc.update("""
                UPDATE iam_platform_admin_grant_requests
                SET identity_id = ?, role_assignment_id = ?, credential_status = ?, newly_granted = ?,
                    setup_caller_client_id = ?
                WHERE request_id = ?
                """, identity.id(), assignment.id(), status.name(), newlyGranted, callerId, requestId);
        if (newlyGranted) outbox.append(events.create(actor, identity.id(), assignment.id(), now, traceId));
        return new PlatformAdminGrant(identity.id(), assignment.id(), status, newlyGranted, callerId, null);
    }

    /** 租约通过原子更新防止多个实例同时发送；崩溃后到期可恢复。 */
    public boolean claimDelivery(UUID requestId) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE iam_platform_admin_grant_requests SET delivery_lease_until = ?
                WHERE request_id = ? AND setup_caller_client_id IS NOT NULL AND setup_state IS NULL
                  AND (delivery_lease_until IS NULL OR delivery_lease_until < ?)
                """, Timestamp.from(now.plus(Duration.ofMinutes(2))), requestId, Timestamp.from(now)) == 1;
    }

    public List<DeliveryWork> pendingDeliveries() {
        return jdbc.query("""
                SELECT request_id, identity_id, setup_caller_client_id
                FROM iam_platform_admin_grant_requests
                WHERE setup_caller_client_id IS NOT NULL AND setup_state IS NULL
                  AND (delivery_lease_until IS NULL OR delivery_lease_until < CURRENT_TIMESTAMP)
                ORDER BY created_at LIMIT 20
                """, (rs, row) -> new DeliveryWork(rs.getObject("request_id", UUID.class),
                rs.getObject("identity_id", UUID.class), rs.getObject("setup_caller_client_id", UUID.class)));
    }

    public void completeDelivery(UUID requestId, SetupState state) {
        jdbc.update("""
                UPDATE iam_platform_admin_grant_requests SET setup_state = ?, delivery_lease_until = NULL
                WHERE request_id = ? AND setup_state IS NULL
                """, state.name(), requestId);
    }

    public record DeliveryWork(UUID requestId, UUID identityId, UUID callerClientId) { }

    private IdentityCredentialStatus credentialStatus(UUID identity, Instant now) {
        var credentials = identities.findCredentials(identity);
        if (credentials.stream().anyMatch(credential ->
                credential.type() == CredentialType.PASSWORD && credential.isValidAt(now))) {
            return IdentityCredentialStatus.PASSWORD_READY;
        }
        return credentials.isEmpty() ? IdentityCredentialStatus.SETUP_ALLOWED
                : IdentityCredentialStatus.RECOVERY_REQUIRED;
    }

    private static void requireV7(UUID id, String field) {
        if (id == null || id.version() != 7) throw new IllegalArgumentException(field + " 必须是 UUIDv7");
    }

    private record GrantRequest(UUID actor, String email, UUID identity, UUID assignment,
            String credentialStatus, Boolean newlyGranted, UUID setupCaller, String setupState) { }
}
