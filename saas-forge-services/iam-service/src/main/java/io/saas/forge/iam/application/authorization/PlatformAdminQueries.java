package io.saas.forge.iam.application.authorization;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只读当前有效固定平台角色；Credential 状态由 IAM 实时数据推导。 */
public class PlatformAdminQueries {
    private final JdbcTemplate jdbc;

    public PlatformAdminQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlatformAdminEntry> list() {
        return jdbc.query("""
                SELECT a.id AS assignment_id, i.id AS identity_id, i.normalized_email,
                       a.assigned_at,
                       EXISTS (SELECT 1 FROM iam_credentials c WHERE c.identity_id = i.id
                           AND c.credential_type = 'PASSWORD' AND c.invalidated_at IS NULL
                           AND (c.expires_at IS NULL OR c.expires_at > CURRENT_TIMESTAMP)) AS password_ready
                FROM iam_platform_role_assignments a JOIN iam_identities i ON i.id = a.identity_id
                WHERE a.role_key = 'PLATFORM_ADMIN' AND a.assigned_at <= CURRENT_TIMESTAMP
                  AND (a.revoked_at IS NULL OR a.revoked_at > CURRENT_TIMESTAMP)
                ORDER BY i.normalized_email
                """, (rs, row) -> new PlatformAdminEntry(
                rs.getObject("identity_id", UUID.class), rs.getObject("assignment_id", UUID.class),
                rs.getString("normalized_email"), rs.getBoolean("password_ready"),
                rs.getTimestamp("assigned_at").toInstant()));
    }

    public record PlatformAdminEntry(UUID identityId, UUID roleAssignmentId,
            String email, boolean passwordReady, Instant assignedAt) { }
}
