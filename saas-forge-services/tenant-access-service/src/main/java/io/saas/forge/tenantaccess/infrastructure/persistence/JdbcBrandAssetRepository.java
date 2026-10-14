package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.brand.BrandAsset;
import io.saas.forge.tenantaccess.application.brand.BrandAssetRepository;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import io.saas.forge.tenantaccess.application.brand.DisabledBrandAsset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBrandAssetRepository implements BrandAssetRepository {
    private final JdbcTemplate jdbc;

    public JdbcBrandAssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
}
