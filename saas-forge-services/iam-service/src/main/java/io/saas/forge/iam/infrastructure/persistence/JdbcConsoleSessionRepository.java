package io.saas.forge.iam.infrastructure.persistence;

import io.saas.forge.iam.domain.session.ConsoleOperation;
import io.saas.forge.iam.domain.session.ConsoleSessionRepository;
import io.saas.forge.iam.domain.session.ConsoleSlot;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConsoleSessionRepository implements ConsoleSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcConsoleSessionRepository(javax.sql.DataSource dataSource) { this.jdbc = new JdbcTemplate(dataSource); }

    @Override
    public ConsoleSlot create(Sha256Digest locator) {
        return jdbc.queryForObject("INSERT INTO iam_console_slots(locator_digest) VALUES (?) RETURNING *",
                this::slot, locator.value());
    }

    @Override
    public Optional<ConsoleSlot> lock(Sha256Digest locator) {
        return jdbc.query("SELECT * FROM iam_console_slots WHERE locator_digest = ? FOR UPDATE",
                this::slot, locator.value()).stream().findFirst();
    }

    @Override
    public void save(ConsoleSlot slot) {
        if (jdbc.update("UPDATE iam_console_slots SET revision = ?, family_id = ?, transition = ? WHERE id = ?",
                slot.revision(), slot.familyId(), slot.transition().name(), slot.id()) != 1) {
            throw new IllegalStateException("Console Slot 保存失败");
        }
    }

    @Override
    public void beginLegacyRetirement() {
        jdbc.update("UPDATE iam_console_protocol SET legacy_blocked = TRUE WHERE singleton = TRUE AND NOT legacy_blocked");
    }

    @Override
    public java.util.List<UUID> pendingLegacyFamilies(int limit) {
        return jdbc.query("SELECT id FROM iam_refresh_token_families WHERE session_protocol = 'LEGACY_V1' AND console_retired_at IS NULL ORDER BY id LIMIT ?",
                (row, index) -> row.getObject(1, UUID.class), limit);
    }

    @Override
    public void confirmLegacyRetirement(UUID familyId) {
        jdbc.update("UPDATE iam_refresh_token_families SET console_retired_at = CURRENT_TIMESTAMP WHERE id = ? AND revoked_at IS NOT NULL", familyId);
    }

    @Override
    public boolean legacyRetirementComplete() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT legacy_blocked AND NOT EXISTS (SELECT 1 FROM iam_refresh_token_families WHERE session_protocol = 'LEGACY_V1' AND console_retired_at IS NULL) FROM iam_console_protocol WHERE singleton = TRUE", Boolean.class));
    }

    @Override
    public boolean isConsoleFamily(UUID familyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM iam_refresh_token_families WHERE id = ? AND session_protocol = 'CONSOLE_V2')",
                Boolean.class, familyId));
    }

    @Override
    public Optional<ConsoleOperation> operation(UUID slotId, UUID key) {
        return jdbc.query("SELECT * FROM iam_console_operations WHERE slot_id = ? AND operation_key = ?",
                (row, index) -> new ConsoleOperation(row.getObject("slot_id", UUID.class),
                        row.getObject("operation_key", UUID.class), row.getObject("family_id", UUID.class),
                        ConsoleOperation.Kind.valueOf(row.getString("kind")), row.getString("fingerprint"),
                        row.getObject("result_revision", Long.class)), slotId, key).stream().findFirst();
    }

    @Override
    public void begin(ConsoleOperation operation) {
        jdbc.update("INSERT INTO iam_console_operations(slot_id, operation_key, family_id, kind, fingerprint) VALUES (?, ?, ?, ?, ?)",
                operation.slotId(), operation.key(), operation.familyId(), operation.kind().name(), operation.fingerprint());
    }

    @Override
    public void complete(UUID slotId, UUID key, long revision) {
        jdbc.update("UPDATE iam_console_operations SET result_revision = ? WHERE slot_id = ? AND operation_key = ?",
                revision, slotId, key);
    }

    @Override
    public void completeLogout(UUID slotId, UUID familyId, long revision) {
        jdbc.update("UPDATE iam_console_operations SET result_revision = ? WHERE slot_id = ? AND family_id = ? AND kind = 'LOGOUT' AND result_revision IS NULL",
                revision, slotId, familyId);
    }

    private ConsoleSlot slot(ResultSet row, int index) throws SQLException {
        return new ConsoleSlot(row.getObject("id", UUID.class), row.getLong("revision"),
                row.getObject("family_id", UUID.class), ConsoleSlot.Transition.valueOf(row.getString("transition")));
    }
}
