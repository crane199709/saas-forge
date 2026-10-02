package io.saas.forge.remotedelivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class ManifestRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    ManifestRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    // 同模块注册和启用串行化；哈希碰撞只降低并发，不扩大权限或破坏正确性。
    void lock(String key) { jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", key).query((rs, index) -> true).single(); }
    Optional<RemoteManifest> version(String module, String version) {
        return jdbc.sql("SELECT * FROM remote_manifests WHERE module=:module AND version=:version")
                .param("module", module).param("version", version).query(ManifestRepository::manifest).optional();
    }
    Optional<RemoteManifest> find(UUID id) {
        return jdbc.sql("SELECT * FROM remote_manifests WHERE id=:id").param("id", id)
                .query(ManifestRepository::manifest).optional();
    }
    RemoteManifest insert(UUID client, RegisterManifestRequest request, Instant at) {
        return jdbc.sql("""
                INSERT INTO remote_manifests (module,version,source,ui_version,entry_sha256,registered_by,registered_at)
                VALUES (:module,:version,:source,:ui,:sha,:client,:at) RETURNING *
                """).param("module", request.module()).param("version", request.version())
                .param("source", request.source()).param("ui", request.uiVersion()).param("sha", request.entrySha256())
                .param("client", client).param("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                .query(ManifestRepository::manifest).single();
    }
    boolean hasEnabled(String module) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM remote_manifests WHERE module=:module AND state='ENABLED')")
                .param("module", module).query(Boolean.class).single();
    }
    RemoteManifest transition(UUID id, String action, UUID actor, Instant at) {
        String sql = action.equals("ENABLE")
                ? "UPDATE remote_manifests SET state='ENABLED',enabled_by=:actor,enabled_at=:at WHERE id=:id AND state='APPROVED' RETURNING *"
                : "UPDATE remote_manifests SET state=:state,reviewed_by=:actor,reviewed_at=:at WHERE id=:id AND state='PENDING_REVIEW' RETURNING *";
        var query = jdbc.sql(sql).param("actor", actor).param("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)).param("id", id);
        if (!action.equals("ENABLE")) query = query.param("state", action.equals("APPROVE") ? "APPROVED" : "REJECTED");
        return query.query(ManifestRepository::manifest).optional()
                .orElseThrow(() -> new ManifestException(409, "MANIFEST_STATE_CONFLICT"));
    }
    Optional<FactReplay> replay(UUID actor, UUID key) {
        return jdbc.sql("SELECT action,manifest_id,result::text FROM remote_manifest_facts WHERE actor_id=:actor AND idempotency_key=:key")
                .param("actor", actor).param("key", key)
                .query((rs, index) -> new FactReplay(rs.getString(1), rs.getObject(2, UUID.class),
                        json.readValue(rs.getString(3), RemoteManifest.class))).optional();
    }
    void fact(String action, UUID actor, UUID key, RemoteManifest manifest, Instant at) {
        jdbc.sql("""
                INSERT INTO remote_manifest_facts (manifest_id,actor_id,action,occurred_at,idempotency_key,result)
                VALUES (:id,:actor,:action,:at,:key,CAST(:result AS jsonb))
                """).param("id", manifest.id()).param("actor", actor).param("action", action)
                .param("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)).param("key", key)
                .param("result", json.writeValueAsString(manifest)).update();
    }
    List<RemoteManifest> list(UUID after, int count, boolean enabledOnly) {
        return jdbc.sql("SELECT * FROM remote_manifests WHERE (:after IS NULL OR id > CAST(:after AS uuid)) "
                        + (enabledOnly ? "AND state='ENABLED' " : "") + "ORDER BY id LIMIT :count")
                .param("after", after == null ? null : after.toString(), java.sql.Types.VARCHAR).param("count", count)
                .query(ManifestRepository::manifest).list();
    }
    record FactReplay(String action, UUID manifestId, RemoteManifest result) {}
    private static RemoteManifest manifest(ResultSet row, int index) throws SQLException {
        return new RemoteManifest(row.getObject("id", UUID.class), row.getString("module"), row.getString("version"),
                row.getString("source"), row.getString("ui_version"), row.getString("entry_sha256"),
                RemoteManifest.State.valueOf(row.getString("state")), row.getObject("registered_by", UUID.class),
                time(row,"registered_at"), row.getObject("reviewed_by",UUID.class), time(row,"reviewed_at"),
                row.getObject("enabled_by",UUID.class), time(row,"enabled_at"));
    }
    private static Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class); return value == null ? null : value.toInstant();
    }
}
