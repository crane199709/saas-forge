package io.saas.forge.iam.application.authorization;

import static io.saas.forge.iam.application.authorization.PlatformAdminRevokeException.Code.*;

import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 平台角色、当前平台 Family、历史平台 jti 与 Outbox 作为一个持久撤权事实提交。 */
public class PlatformAdminRevokeTransaction {
    private final JdbcTemplate jdbc;
    private final OutboxEventRepository outbox;
    private final PlatformAdminRevokedEventFactory events;
    private final Clock clock;

    public PlatformAdminRevokeTransaction(JdbcTemplate jdbc, OutboxEventRepository outbox,
            PlatformAdminRevokedEventFactory events, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public boolean prepare(UUID actor, UUID target, UUID requestId, String traceId) {
        requireV7(actor); requireV7(target); requireV7(requestId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("""
                INSERT INTO iam_platform_admin_revoke_requests
                    (request_id, actor_identity_id, target_identity_id, created_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING
                """, requestId, actor, target, Timestamp.from(now));
        Request request = jdbc.queryForObject("""
                SELECT actor_identity_id, target_identity_id, role_assignment_id, completed_at
                FROM iam_platform_admin_revoke_requests WHERE request_id = ? FOR UPDATE
                """, (rs, row) -> new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getTimestamp(4)), requestId);
        if (request == null || !request.actor().equals(actor) || !request.target().equals(target))
            throw new PlatformAdminRevokeException(REQUEST_CONFLICT);
        if (request.assignment() != null) return request.completedAt() != null;

        // 所有撤权共用一把事务锁，两个并发请求不能同时通过最后一名密码管理员检查。
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(1733883632, 1)", Object.class);
        List<UUID> identities = jdbc.query("SELECT id FROM iam_identities WHERE id = ? FOR NO KEY UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), target);
        if (identities.isEmpty()) throw new PlatformAdminRevokeException(TARGET_UNAVAILABLE);
        List<UUID> active = jdbc.query("""
                SELECT id FROM iam_platform_role_assignments
                WHERE identity_id = ? AND role_key = 'PLATFORM_ADMIN' AND assigned_at <= ? AND revoked_at IS NULL
                FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), target, Timestamp.from(now));
        if (active.isEmpty()) throw new PlatformAdminRevokeException(TARGET_UNAVAILABLE);
        Boolean survivor = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM iam_platform_role_assignments a
                    JOIN iam_credentials c ON c.identity_id = a.identity_id
                    WHERE a.role_key = 'PLATFORM_ADMIN' AND a.assigned_at <= ? AND a.revoked_at IS NULL
                      AND a.identity_id <> ? AND c.credential_type = 'PASSWORD'
                      AND c.invalidated_at IS NULL
                      AND (c.expires_at IS NULL OR c.expires_at > ?)
                )
                """, Boolean.class, Timestamp.from(now), target, Timestamp.from(now));
        if (!Boolean.TRUE.equals(survivor)) throw new PlatformAdminRevokeException(LAST_PASSWORD_ADMIN);

        UUID assignment = active.get(0);
        jdbc.update("UPDATE iam_platform_role_assignments SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(now), assignment);
        // Family 已切到 Tenant 时不结束；签发记录仍保留先前平台 jti 的上下文。
        jdbc.update("""
                UPDATE iam_refresh_token_families SET revoked_at = GREATEST(last_used_at, ?)
                WHERE identity_id = ? AND family_purpose = 'USER_PLATFORM' AND revoked_at IS NULL
                """, Timestamp.from(now), target);
        jdbc.update("""
                UPDATE iam_access_token_issuances
                SET revoked_at = ?, revocation_reason = 'PLATFORM_ROLE_REVOKED'
                WHERE identity_id = ? AND membership_id IS NULL AND tenant_id IS NULL
                  AND expires_at + INTERVAL '30 seconds' > ? AND revoked_at IS NULL
                """, Timestamp.from(now), target, Timestamp.from(now));
        jdbc.update("""
                UPDATE iam_platform_admin_revoke_requests SET role_assignment_id = ? WHERE request_id = ?
                """, assignment, requestId);
        outbox.append(events.create(actor, target, assignment, now, traceId));
        return false;
    }

    public List<Jti> pendingJtis(UUID requestId) {
        return jdbc.query("""
                SELECT issuance.jti, issuance.expires_at
                FROM iam_platform_admin_revoke_requests request
                JOIN iam_access_token_issuances issuance ON issuance.identity_id = request.target_identity_id
                WHERE request.request_id = ? AND request.role_assignment_id IS NOT NULL
                  AND issuance.membership_id IS NULL AND issuance.tenant_id IS NULL
                  AND issuance.revoked_at IS NOT NULL
                  AND issuance.expires_at + INTERVAL '30 seconds' > CURRENT_TIMESTAMP
                ORDER BY issuance.expires_at, issuance.jti
                """, (rs, row) -> new Jti(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), requestId);
    }

    public List<UUID> pendingRequests() {
        return jdbc.query("""
                SELECT request_id FROM iam_platform_admin_revoke_requests
                WHERE role_assignment_id IS NOT NULL AND completed_at IS NULL
                ORDER BY created_at LIMIT 20
                """, (rs, row) -> rs.getObject(1, UUID.class));
    }

    public void complete(UUID requestId) {
        jdbc.update("""
                UPDATE iam_platform_admin_revoke_requests SET completed_at = ?
                WHERE request_id = ? AND role_assignment_id IS NOT NULL AND completed_at IS NULL
                """, Timestamp.from(clock.instant()), requestId);
    }

    public record Jti(UUID jti, Instant expiresAt) { }
    private record Request(UUID actor, UUID target, UUID assignment, Timestamp completedAt) { }

    private static void requireV7(UUID value) {
        if (value == null || value.version() != 7) throw new IllegalArgumentException("UUID 必须是 UUIDv7");
    }
}
