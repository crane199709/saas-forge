ALTER TABLE tenant_brand_assets
    ADD COLUMN disabled_at TIMESTAMPTZ,
    ADD COLUMN disabled_by UUID;

-- 违规禁用不可恢复；REMOVED 仅供后续对象清理，仍不得恢复公开读取。
CREATE FUNCTION prevent_disabled_brand_asset_reactivation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('DISABLED', 'REMOVED') AND NEW.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'Policy disabled brand asset cannot be reactivated';
    END IF;
    IF OLD.disabled_at IS NOT NULL AND
       (NEW.disabled_at IS DISTINCT FROM OLD.disabled_at OR NEW.disabled_by IS DISTINCT FROM OLD.disabled_by) THEN
        RAISE EXCEPTION 'Policy disable metadata is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tenant_brand_asset_policy_immutable
    BEFORE UPDATE ON tenant_brand_assets
    FOR EACH ROW EXECUTE FUNCTION prevent_disabled_brand_asset_reactivation();

-- 平台入口先验证当前 PLATFORM_ADMIN；函数仅按精确素材 ID 更新，受限账号不获得跨 Tenant 表枚举权。
CREATE FUNCTION disable_brand_asset(p_id UUID, p_actor UUID)
RETURNS TABLE (tenant_id UUID, kind VARCHAR(7), changed BOOLEAN, disabled_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE current_asset RECORD;
BEGIN
    SELECT asset.tenant_id, asset.kind, asset.status, asset.disabled_at INTO current_asset
    FROM public.tenant_brand_assets AS asset WHERE asset.id = p_id FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    IF current_asset.status = 'ACTIVE' THEN
        UPDATE public.tenant_brand_assets AS asset
        SET status = 'DISABLED', disabled_at = pg_catalog.clock_timestamp(), disabled_by = p_actor
        WHERE asset.id = p_id
        RETURNING asset.disabled_at INTO disabled_at;
        changed := true;
    ELSIF current_asset.disabled_at IS NOT NULL THEN
        disabled_at := current_asset.disabled_at;
        changed := false;
    ELSE
        RETURN;
    END IF;
    tenant_id := current_asset.tenant_id;
    kind := current_asset.kind;
    PERFORM pg_catalog.set_config('app.tenant_id', tenant_id::TEXT, true);
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION disable_brand_asset(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION disable_brand_asset(UUID, UUID) TO tenant_access_app;

-- IAM 每次权威读取仅取得完整有效 Profile；旧绑定仍保留在 Tenant 管理读模型供修复。
CREATE OR REPLACE FUNCTION list_accessible_membership_contexts(p_identity_id UUID)
RETURNS TABLE (
    membership_id UUID, tenant_id UUID, tenant_display_name VARCHAR(200),
    brand_display_name VARCHAR(200), brand_logo_url VARCHAR(2048), brand_favicon_url VARCHAR(2048),
    brand_primary_color CHAR(7), brand_accent_color CHAR(7))
LANGUAGE SQL STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$
    SELECT membership.id, tenant.id, tenant.display_name, brand.display_name, brand.logo_url,
           brand.favicon_url, brand.primary_color, brand.accent_color
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    LEFT JOIN public.tenant_brand_profiles AS brand ON brand.tenant_id = tenant.id
        AND EXISTS (SELECT 1 FROM public.tenant_brand_assets AS logo
                    WHERE logo.tenant_id = tenant.id AND logo.kind = 'LOGO' AND logo.status = 'ACTIVE'
                      AND brand.logo_url = '/brands/' || logo.id::TEXT)
        AND EXISTS (SELECT 1 FROM public.tenant_brand_assets AS favicon
                    WHERE favicon.tenant_id = tenant.id AND favicon.kind = 'FAVICON' AND favicon.status = 'ACTIVE'
                      AND brand.favicon_url = '/brands/' || favicon.id::TEXT)
    WHERE membership.identity_id = p_identity_id AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_lifecycle_workflows AS closure
          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
      AND NOT EXISTS (
          SELECT 1 FROM public.membership_disable_workflows AS disabling
          WHERE disabling.tenant_id = membership.tenant_id AND disabling.membership_id = membership.id
            AND disabling.workflow_status IN ('REVOKING', 'RECOVERY_REQUIRED', 'RELEASE_PENDING'))
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
    ORDER BY tenant.display_name COLLATE "C", membership.id LIMIT 101
$$;
