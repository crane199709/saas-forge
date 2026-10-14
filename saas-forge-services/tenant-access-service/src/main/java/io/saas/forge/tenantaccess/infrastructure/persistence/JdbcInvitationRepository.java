package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.invitation.InvitationRepository;
import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitationRepository implements InvitationRepository {
    private final JdbcTemplate jdbc;

    public JdbcInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void setTarget(UUID tenantId) {
        String applied = jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                tenantId.toString());
        if (!tenantId.toString().equals(applied)) throw new IllegalStateException("Tenant Operation Target 设置失败");
    }

    @Override
    public boolean lockAccessibleTenant(UUID tenantId, Instant now) {
        return !jdbc.query("""
                SELECT id FROM tenants WHERE id = ? AND tenant_status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > ?) FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), tenantId, Timestamp.from(now)).isEmpty();
    }

    @Override
    public boolean membershipExists(UUID tenantId, UUID identityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM memberships WHERE tenant_id = ? AND identity_id = ?)",
                Boolean.class, tenantId, identityId));
    }

    @Override
    public boolean pendingEmailExists(UUID tenantId, String email, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invitations WHERE tenant_id = ? AND email = ?
                  AND invitation_status = 'PENDING' AND expires_at > ?)
                """, Boolean.class, tenantId, email, Timestamp.from(now)));
    }

    @Override
    public void insert(Invitation invitation, UUID identityId, UUID actorIdentityId) {
        jdbc.update("""
                INSERT INTO invitations (id, tenant_id, identity_id, email, token_hash,
                    invitation_status, expires_at, created_at, updated_at, created_by_identity_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, invitation.id(), invitation.tenantId(), identityId, invitation.email(),
                invitation.tokenHash(), invitation.status().name(), Timestamp.from(invitation.expiresAt()),
                Timestamp.from(invitation.createdAt()), Timestamp.from(invitation.updatedAt()), actorIdentityId);
    }

    @Override
    public Invitation lock(UUID tenantId, UUID invitationId) {
        List<Invitation> rows = jdbc.query("""
                SELECT id, tenant_id, email, token_hash, invitation_status, expires_at, created_at, updated_at
                FROM invitations WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, JdbcInvitationRepository::invitation, tenantId, invitationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public int generation(UUID tenantId, UUID invitationId) {
        return jdbc.queryForObject(
                "SELECT token_generation FROM invitations WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, invitationId);
    }

    @Override
    public void saveResend(Invitation invitation) {
        jdbc.update("""
                UPDATE invitations SET token_hash = ?, token_generation = token_generation + 1,
                    delivery_status = 'PENDING', delivered_at = NULL, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """, invitation.tokenHash(), Timestamp.from(invitation.updatedAt()),
                invitation.tenantId(), invitation.id());
    }

    @Override
    public void saveRevocation(Invitation invitation) {
        jdbc.update("""
                UPDATE invitations SET invitation_status = 'REVOKED', updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """, Timestamp.from(invitation.updatedAt()), invitation.tenantId(), invitation.id());
    }

    @Override
    public void markDelivery(UUID tenantId, UUID invitationId, int generation, boolean delivered, Instant now) {
        jdbc.update("""
                UPDATE invitations SET delivery_status = ?, delivered_at = ?
                WHERE tenant_id = ? AND id = ? AND token_generation = ? AND invitation_status = 'PENDING'
                """, delivered ? "SENT" : "FAILED", delivered ? Timestamp.from(now) : null,
                tenantId, invitationId, generation);
    }

    @Override
    public List<InvitationSummary> list(UUID tenantId, Instant now) {
        return jdbc.query("""
                SELECT id, email, invitation_status, delivery_status, expires_at, created_at, updated_at
                FROM invitations WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT 100
                """, (row, index) -> new InvitationSummary(row.getObject(1, UUID.class), row.getString(2),
                        "PENDING".equals(row.getString(3)) && !row.getTimestamp(5).toInstant().isAfter(now)
                                ? "EXPIRED" : row.getString(3),
                        row.getString(4), row.getTimestamp(5).toInstant(),
                        row.getTimestamp(6).toInstant(), row.getTimestamp(7).toInstant()), tenantId);
    }

    private static Invitation invitation(ResultSet row, int index) throws SQLException {
        return new Invitation(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getString("email"), row.getString("token_hash"),
                Invitation.Status.valueOf(row.getString("invitation_status")),
                row.getTimestamp("expires_at").toInstant(), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
