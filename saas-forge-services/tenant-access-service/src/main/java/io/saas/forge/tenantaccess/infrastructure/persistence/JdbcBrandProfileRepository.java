package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.brand.BrandProfileChange;
import io.saas.forge.tenantaccess.application.brand.BrandProfileRepository;
import io.saas.forge.tenantaccess.application.brand.BrandProfileRevision;
import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBrandProfileRepository implements BrandProfileRepository {
    private final JdbcTemplate jdbc;

    public JdbcBrandProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public BrandProfileRevision read(UUID tenantId) {
        target(tenantId);
        return current(tenantId);
    }

    @Override
    public BrandProfileRevision save(UUID tenantId, long expectedRevision, BrandProfileChange change) {
        target(tenantId);
        // Tenant 行锁也串行化尚无 Profile 的两个首写，避免同时按 revision 0 插入。
        if (jdbc.query("SELECT id FROM tenants WHERE id = ? FOR UPDATE", (row, index) -> row.getObject(1, UUID.class),
                tenantId).isEmpty()) throw new BrandProfileService.Forbidden();
        BrandProfileRevision current = current(tenantId);
        if (current.revision() != expectedRevision) throw new BrandProfileService.Stale();
        requireAsset(tenantId, BrandProfileService.assetId(change.logoUrl()), "LOGO");
        requireAsset(tenantId, BrandProfileService.assetId(change.faviconUrl()), "FAVICON");
        long next = current.revision() + 1;
        if (current.revision() == 0) {
            jdbc.update("""
                    INSERT INTO tenant_brand_profiles
                        (tenant_id, display_name, logo_url, favicon_url, primary_color, accent_color, revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, change.displayName(), change.logoUrl(), change.faviconUrl(),
                    change.primaryColor(), change.accentColor(), next);
        } else {
            jdbc.update("""
                    UPDATE tenant_brand_profiles SET display_name = ?, logo_url = ?, favicon_url = ?,
                        primary_color = ?, accent_color = ?, revision = ? WHERE tenant_id = ?
                    """, change.displayName(), change.logoUrl(), change.faviconUrl(), change.primaryColor(),
                    change.accentColor(), next, tenantId);
        }
        return new BrandProfileRevision(next, change);
    }

    private BrandProfileRevision current(UUID tenantId) {
        return jdbc.query("""
                SELECT revision, display_name, logo_url, favicon_url, primary_color, accent_color
                FROM tenant_brand_profiles WHERE tenant_id = ?
                """, (row, index) -> new BrandProfileRevision(row.getLong(1), new BrandProfileChange(
                        row.getString(2), row.getString(3), row.getString(4), row.getString(5), row.getString(6))),
                tenantId).stream().findFirst().orElse(new BrandProfileRevision(0, null));
    }

    private void requireAsset(UUID tenantId, UUID assetId, String kind) {
        if (jdbc.query("""
                SELECT id FROM tenant_brand_assets
                WHERE id = ? AND tenant_id = ? AND kind = ? AND status = 'ACTIVE' FOR SHARE
                """, (row, index) -> row.getObject(1, UUID.class), assetId, tenantId, kind).isEmpty()) {
            throw new BrandProfileService.AssetUnavailable();
        }
    }

    private void target(UUID tenantId) {
        if (!tenantId.toString().equals(jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString()))) throw new IllegalStateException("Tenant Operation Target 设置失败");
    }
}
