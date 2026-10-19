package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceRepository;
import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitationAcceptanceRepository implements InvitationAcceptanceRepository {
    private final JdbcTemplate jdbc;

    public JdbcInvitationAcceptanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public Target locate(String tokenHash) {
        List<Target> rows = jdbc.query(
                "SELECT invitation_id, tenant_id FROM locate_invitation_by_token_hash(?::char(64))",
                (row, index) -> new Target(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), tokenHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override public void setTarget(UUID tenantId) {
        String applied = jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                tenantId.toString());
        if (!tenantId.toString().equals(applied)) throw new IllegalStateException("Tenant Operation Target 设置失败");
    }

    @Override public boolean lockAccessibleTenant(UUID tenantId, Instant now) {
        return !jdbc.query("""
                SELECT id FROM tenants WHERE id = ? AND tenant_status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > ?) FOR UPDATE
                """, (row, index) -> row.getObject(1, UUID.class), tenantId, Timestamp.from(now)).isEmpty();
    }

    @Override public Invitation lockInvitation(UUID tenantId, UUID invitationId) {
        List<Invitation> rows = jdbc.query("""
                SELECT id, tenant_id, email, token_hash, invitation_status, expires_at, created_at, updated_at
                FROM invitations WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, (row, index) -> new Invitation(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getString(3), row.getString(4), Invitation.Status.valueOf(row.getString(5)),
                row.getTimestamp(6).toInstant(), row.getTimestamp(7).toInstant(),
                row.getTimestamp(8).toInstant()), tenantId, invitationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override public UUID invitedIdentity(UUID tenantId, UUID invitationId) {
        return jdbc.queryForObject("SELECT identity_id FROM invitations WHERE tenant_id = ? AND id = ?",
                UUID.class, tenantId, invitationId);
    }

    @Override public boolean membershipExists(UUID tenantId, UUID identityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM memberships WHERE tenant_id = ? AND identity_id = ?)
                """, Boolean.class, tenantId, identityId));
    }

    @Override public Attempt lockAttempt(UUID tenantId, UUID invitationId) {
        List<Attempt> rows = jdbc.query("""
                SELECT invitation_id, tenant_id, identity_id, idempotency_key, consume_operation_id,
                       release_operation_id, membership_id, state
                FROM invitation_acceptance_attempts
                WHERE tenant_id = ? AND invitation_id = ? FOR UPDATE
                """, (row, index) -> new Attempt(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                row.getObject(6, UUID.class), row.getObject(7, UUID.class), row.getString(8)),
                tenantId, invitationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override public Attempt claimNextCompensation(Instant now) {
        List<Attempt> rows = jdbc.query("SELECT * FROM claim_next_invitation_compensation(?)",
                (row, index) -> new Attempt(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                        row.getObject(6, UUID.class), row.getObject(7, UUID.class), row.getString(8)),
                Timestamp.from(now));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override public void saveAttempt(Attempt attempt, Instant now) {
        jdbc.update("""
                INSERT INTO invitation_acceptance_attempts
                    (invitation_id, tenant_id, identity_id, idempotency_key, consume_operation_id,
                     release_operation_id, membership_id, state, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (invitation_id) DO UPDATE SET
                    identity_id = EXCLUDED.identity_id, idempotency_key = EXCLUDED.idempotency_key,
                    consume_operation_id = EXCLUDED.consume_operation_id,
                    release_operation_id = EXCLUDED.release_operation_id,
                    membership_id = EXCLUDED.membership_id, state = EXCLUDED.state,
                    updated_at = EXCLUDED.updated_at
                WHERE invitation_acceptance_attempts.state = 'COMPENSATED'
                """, attempt.invitationId(), attempt.tenantId(), attempt.identityId(), attempt.key(),
                attempt.consumeId(), attempt.releaseId(), attempt.membershipId(), attempt.state(),
                Timestamp.from(now));
    }

    @Override public void state(Attempt attempt, String state, Instant now) {
        jdbc.update("""
                UPDATE invitation_acceptance_attempts SET state = ?, updated_at = ?
                WHERE tenant_id = ? AND invitation_id = ? AND idempotency_key = ?
                """, state, Timestamp.from(now), attempt.tenantId(), attempt.invitationId(), attempt.key());
    }

    @Override public void activate(Attempt attempt, Instant now) {
        jdbc.update("""
                INSERT INTO memberships (id, tenant_id, identity_id, membership_status)
                VALUES (?, ?, ?, 'ENABLED')
                """, attempt.membershipId(), attempt.tenantId(), attempt.identityId());
        jdbc.update("""
                UPDATE invitations SET invitation_status = 'ACCEPTED', updated_at = ?
                WHERE tenant_id = ? AND id = ? AND invitation_status = 'PENDING'
                """, Timestamp.from(now), attempt.tenantId(), attempt.invitationId());
        state(attempt, "SUCCEEDED", now);
    }
}
