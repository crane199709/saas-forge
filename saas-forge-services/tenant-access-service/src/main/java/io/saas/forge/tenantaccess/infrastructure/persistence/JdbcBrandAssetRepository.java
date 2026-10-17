package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.brand.BrandAsset;
import io.saas.forge.tenantaccess.application.brand.BrandAssetRepository;
import io.saas.forge.tenantaccess.application.brand.BrandAssetCleanupRepository;
import io.saas.forge.tenantaccess.application.brand.BrandUploadIntentRepository;
import io.saas.forge.tenantaccess.application.brand.ClaimedBrandAsset;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import io.saas.forge.tenantaccess.application.brand.DisabledBrandAsset;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBrandAssetRepository implements BrandAssetRepository, BrandAssetCleanupRepository,
        BrandUploadIntentRepository {
    private final JdbcTemplate jdbc;

    public JdbcBrandAssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void reserveUpload(BrandAsset asset, String objectKey) {
        target(asset.tenantId());
        jdbc.update("INSERT INTO brand_upload_intents (asset_id, tenant_id, object_key) VALUES (?, ?, ?)",
                asset.id(), asset.tenantId(), objectKey);
    }

    @Override public void completeUpload(UUID tenantId, UUID assetId) {
        target(tenantId);
        if (jdbc.update("DELETE FROM brand_upload_intents WHERE tenant_id = ? AND asset_id = ? AND state = 'RESERVED'",
                tenantId, assetId) != 1) throw new IllegalStateException("品牌上传已被清理领取");
    }

    @Override public void discardUpload(UUID tenantId, UUID assetId) {
        target(tenantId);
        jdbc.update("DELETE FROM brand_upload_intents WHERE tenant_id = ? AND asset_id = ? AND state = 'RESERVED'",
                tenantId, assetId);
    }

    @Override public Optional<Intent> claimUpload(Instant now) {
        return jdbc.query("SELECT asset_id, tenant_id, object_key FROM claim_next_brand_upload_cleanup(?)",
                (row, index) -> new Intent(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getString(3)), java.sql.Timestamp.from(now)).stream().findFirst();
    }

    @Override public void completeUploadCleanup(Intent intent) {
        target(intent.tenantId());
        jdbc.update("DELETE FROM brand_upload_intents WHERE tenant_id = ? AND asset_id = ? AND state = 'CLEANING'",
                intent.tenantId(), intent.assetId());
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    @Override
    public void insert(BrandAsset asset, String objectKey, int byteSize) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                asset.tenantId().toString());
        jdbc.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, asset.id(), asset.tenantId(), asset.kind().name(), objectKey, byteSize,
                asset.width(), asset.height());
    }

    @Override
    public Optional<String> publicKey(UUID assetId) {
        return jdbc.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), assetId).stream().findFirst();
    }

    @Override
    public Optional<DisabledBrandAsset> disable(UUID assetId, UUID actorIdentityId) {
        return jdbc.query("SELECT tenant_id, kind, changed, disabled_at FROM disable_brand_asset(?, ?)",
                (row, index) -> new DisabledBrandAsset(assetId, row.getObject(1, UUID.class),
                        BrandPng.Kind.valueOf(row.getString(2)), row.getTimestamp(4).toInstant(),
                        row.getBoolean(3)), assetId, actorIdentityId).stream().findFirst();
    }

    @Override
    public Optional<ClaimedBrandAsset> claim(Instant now) {
        return jdbc.query("SELECT asset_id, tenant_id, object_key, kind, reason FROM claim_expired_brand_asset(?)",
                (row, index) -> new ClaimedBrandAsset(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getString(3), BrandPng.Kind.valueOf(row.getString(4)),
                        ClaimedBrandAsset.Reason.valueOf(row.getString(5))),
                java.sql.Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public boolean complete(ClaimedBrandAsset asset, Instant now) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                asset.tenantId().toString());
        return jdbc.update("""
                UPDATE tenant_brand_assets
                SET status = 'REMOVED', removed_at = ?, cleanup_next_attempt_at = NULL
                WHERE id = ? AND tenant_id = ? AND status = 'REMOVING'
                """, java.sql.Timestamp.from(now), asset.id(), asset.tenantId()) == 1;
    }
}
