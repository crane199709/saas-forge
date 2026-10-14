package io.saas.forge.iam.infrastructure.persistence;

import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;

/** Password Recovery Challenge 与根工作流的持久状态。 */
@Repository
public class PasswordRecoveryRepository {
    private final JdbcTemplate jdbc;

    public PasswordRecoveryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Challenge replace(UUID identityId, Sha256Digest digest, Instant at) {
        jdbc.update("UPDATE iam_password_recovery_challenges SET invalidated_at = ? "
                + "WHERE identity_id = ? AND invalidated_at IS NULL AND started_at IS NULL", Timestamp.from(at), identityId);
        return jdbc.queryForObject("INSERT INTO iam_password_recovery_challenges "
                + "(identity_id, token_digest, issued_at, expires_at) VALUES (?, ?, ?, ?) "
                + "RETURNING id, identity_id, issued_at, expires_at, invalidated_at, started_at, completed_at, credential_id",
                PasswordRecoveryRepository::map, identityId, digest.value(), Timestamp.from(at),
                Timestamp.from(at.plusSeconds(1800)));
    }

    public Optional<Challenge> find(Sha256Digest digest) {
        return one("SELECT id, identity_id, issued_at, expires_at, invalidated_at, started_at, completed_at, "
                + "credential_id FROM iam_password_recovery_challenges WHERE token_digest = ?", digest.value());
    }

    public Optional<Challenge> lock(Sha256Digest digest) {
        return one("SELECT id, identity_id, issued_at, expires_at, invalidated_at, started_at, completed_at, "
                + "credential_id FROM iam_password_recovery_challenges WHERE token_digest = ? FOR UPDATE", digest.value());
    }

    public Optional<Challenge> lockById(UUID id) {
        return jdbc.query("SELECT id, identity_id, issued_at, expires_at, invalidated_at, started_at, "
                + "completed_at, credential_id FROM iam_password_recovery_challenges WHERE id = ? FOR UPDATE",
                PasswordRecoveryRepository::map, id).stream().findFirst();
    }

    public void start(UUID challengeId, UUID credentialId, Instant at) {
        if (jdbc.update("UPDATE iam_password_recovery_challenges SET started_at = ?, credential_id = ? "
                + "WHERE id = ? AND started_at IS NULL AND invalidated_at IS NULL AND expires_at > ?",
                Timestamp.from(at), credentialId, challengeId, Timestamp.from(at)) != 1) {
            throw new IllegalStateException("Password Recovery 开始事实保存失败");
        }
    }

    public boolean complete(UUID challengeId, Instant at) {
        return jdbc.update("UPDATE iam_password_recovery_challenges SET completed_at = ? "
                + "WHERE id = ? AND started_at IS NOT NULL AND completed_at IS NULL", Timestamp.from(at), challengeId) == 1;
    }

    public List<Challenge> unfinished() {
        return jdbc.query("SELECT id, identity_id, issued_at, expires_at, invalidated_at, started_at, completed_at, "
                + "credential_id FROM iam_password_recovery_challenges "
                + "WHERE started_at IS NOT NULL AND completed_at IS NULL ORDER BY started_at LIMIT 100",
                PasswordRecoveryRepository::map);
    }

    private Optional<Challenge> one(String sql, byte[] digest) {
        return jdbc.query(sql, PasswordRecoveryRepository::map, digest).stream().findFirst();
    }

    private static Challenge map(ResultSet row, int ignored) throws SQLException {
        return new Challenge(row.getObject("id", UUID.class), row.getObject("identity_id", UUID.class),
                row.getTimestamp("issued_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                instant(row, "invalidated_at"), instant(row, "started_at"), instant(row, "completed_at"),
                row.getObject("credential_id", UUID.class));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Challenge(UUID id, UUID identityId, Instant issuedAt, Instant expiresAt,
                            Instant invalidatedAt, Instant startedAt, Instant completedAt, UUID credentialId) { }
}
