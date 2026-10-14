CREATE TABLE tenant_brand_assets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    kind VARCHAR(7) NOT NULL CHECK (kind IN ('LOGO', 'FAVICON')),
    object_key VARCHAR(200) NOT NULL UNIQUE,
    byte_size INTEGER NOT NULL CHECK (byte_size BETWEEN 1 AND 2097152),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 2048),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 2048),
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'REMOVED')),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_tenant_brand_asset_id_v7 CHECK (uuid_extract_version(id) = 7)
);
CREATE INDEX ix_tenant_brand_assets_tenant ON tenant_brand_assets (tenant_id, uploaded_at);

ALTER TABLE tenant_brand_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_brand_assets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_brand_assets_app ON tenant_brand_assets FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_brand_assets_migrator ON tenant_brand_assets FOR ALL TO tenant_access_migrator
    USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON tenant_brand_assets TO tenant_access_app;

-- 仅通过精确素材 ID 暴露有效对象键，不授予跨 Tenant 表查询或目录枚举能力。
CREATE FUNCTION read_public_brand_asset(p_id UUID)
RETURNS TABLE (object_key VARCHAR(200), byte_size INTEGER)
LANGUAGE SQL STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
    SELECT asset.object_key, asset.byte_size FROM public.tenant_brand_assets asset
    WHERE asset.id = p_id AND asset.status = 'ACTIVE'
$$;
REVOKE ALL ON FUNCTION read_public_brand_asset(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION read_public_brand_asset(UUID) TO tenant_access_app;

INSERT INTO permission_catalog
    (code, semantic_id, source, enabled, first_revision, last_revision, updated_at)
VALUES ('system:brand:manage', 'manageBrandAssets', 'tenant-access-service', true, 1, 1, statement_timestamp());
