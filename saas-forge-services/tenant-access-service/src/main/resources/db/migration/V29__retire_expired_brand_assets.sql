ALTER TABLE tenant_brand_assets
    ADD COLUMN ever_bound_at TIMESTAMPTZ,
    ADD COLUMN retired_at TIMESTAMPTZ,
    ADD COLUMN removing_at TIMESTAMPTZ,
    ADD COLUMN removed_at TIMESTAMPTZ,
    ADD COLUMN cleanup_reason VARCHAR(8) CHECK (cleanup_reason IN ('UNBOUND', 'REPLACED', 'DISABLED', 'LEGACY')),
    ADD COLUMN cleanup_next_attempt_at TIMESTAMPTZ;

ALTER TABLE tenant_brand_assets DROP CONSTRAINT tenant_brand_assets_status_check;
ALTER TABLE tenant_brand_assets ADD CONSTRAINT tenant_brand_assets_status_check
    CHECK (status IN ('ACTIVE', 'DISABLED', 'REMOVING', 'REMOVED'));

-- 旧素材没有绑定历史。当前引用回填绑定事实，其他旧 ACTIVE 素材保守留存七天，避免把历史替换误判为未绑定。
UPDATE tenant_brand_assets AS asset SET ever_bound_at = asset.uploaded_at
FROM tenant_brand_profiles AS profile
WHERE asset.tenant_id = profile.tenant_id
  AND (profile.logo_url = '/brands/' || asset.id::TEXT
       OR profile.favicon_url = '/brands/' || asset.id::TEXT);
UPDATE tenant_brand_assets SET retired_at = statement_timestamp()
WHERE status = 'ACTIVE' AND ever_bound_at IS NULL;

CREATE INDEX ix_tenant_brand_assets_cleanup ON tenant_brand_assets (status, cleanup_next_attempt_at, uploaded_at);

-- 先锁 Tenant 再锁素材，与 Profile 保存的锁顺序一致；领取时复核当前引用，避免并发绑定后删除。
CREATE FUNCTION claim_expired_brand_asset(p_now TIMESTAMPTZ)
RETURNS TABLE (asset_id UUID, tenant_id UUID, object_key VARCHAR(200), kind VARCHAR(7), reason VARCHAR(8))
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_id UUID;
DECLARE v_tenant UUID;
DECLARE v_asset RECORD;
DECLARE v_reason VARCHAR(8);
BEGIN
    SELECT asset.id, asset.tenant_id INTO v_id, v_tenant
    FROM public.tenant_brand_assets AS asset
    WHERE ((asset.status = 'REMOVING' AND asset.cleanup_next_attempt_at <= p_now)
       OR (asset.status = 'ACTIVE' AND asset.ever_bound_at IS NULL
           AND asset.retired_at IS NULL AND asset.uploaded_at <= p_now - INTERVAL '24 hours')
       OR (asset.status = 'ACTIVE' AND asset.retired_at <= p_now - INTERVAL '7 days')
       OR (asset.status = 'DISABLED' AND asset.disabled_at <= p_now - INTERVAL '7 days'))
      AND (asset.status <> 'ACTIVE' OR NOT EXISTS (
          SELECT 1 FROM public.tenant_brand_profiles AS profile
          WHERE profile.tenant_id = asset.tenant_id
            AND (profile.logo_url = '/brands/' || asset.id::TEXT
                 OR profile.favicon_url = '/brands/' || asset.id::TEXT)))
    ORDER BY CASE WHEN asset.status = 'REMOVING' THEN 0 ELSE 1 END,
             COALESCE(asset.cleanup_next_attempt_at, asset.retired_at, asset.disabled_at, asset.uploaded_at), asset.id
    LIMIT 1;
    IF v_id IS NULL THEN RETURN; END IF;
    PERFORM 1 FROM public.tenants WHERE id = v_tenant FOR UPDATE;
    SELECT asset.id, asset.tenant_id, asset.object_key, asset.kind, asset.status, asset.uploaded_at,
           asset.ever_bound_at, asset.retired_at, asset.disabled_at, asset.cleanup_next_attempt_at,
           asset.cleanup_reason INTO v_asset
    FROM public.tenant_brand_assets AS asset WHERE asset.id = v_id FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    IF v_asset.status = 'REMOVING' THEN
        IF v_asset.cleanup_next_attempt_at > p_now THEN RETURN; END IF;
        v_reason := v_asset.cleanup_reason;
    ELSIF v_asset.status = 'DISABLED' AND v_asset.disabled_at <= p_now - INTERVAL '7 days' THEN
        v_reason := 'DISABLED';
    ELSIF v_asset.status = 'ACTIVE' AND v_asset.retired_at <= p_now - INTERVAL '7 days' THEN
        v_reason := CASE WHEN v_asset.ever_bound_at IS NULL THEN 'LEGACY' ELSE 'REPLACED' END;
    ELSIF v_asset.status = 'ACTIVE' AND v_asset.ever_bound_at IS NULL
          AND v_asset.retired_at IS NULL AND v_asset.uploaded_at <= p_now - INTERVAL '24 hours' THEN
        v_reason := 'UNBOUND';
    ELSE
        RETURN;
    END IF;
    IF v_asset.status = 'ACTIVE' AND EXISTS (
        SELECT 1 FROM public.tenant_brand_profiles AS profile
        WHERE profile.tenant_id = v_tenant
          AND (profile.logo_url = '/brands/' || v_id::TEXT
               OR profile.favicon_url = '/brands/' || v_id::TEXT)) THEN
        RETURN;
    END IF;
    UPDATE public.tenant_brand_assets AS asset
    SET status = 'REMOVING', removing_at = COALESCE(asset.removing_at, p_now),
        cleanup_reason = v_reason, cleanup_next_attempt_at = p_now + INTERVAL '5 minutes'
    WHERE asset.id = v_id;
    asset_id := v_id;
    tenant_id := v_tenant;
    object_key := v_asset.object_key;
    kind := v_asset.kind;
    reason := v_reason;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION claim_expired_brand_asset(TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_expired_brand_asset(TIMESTAMPTZ) TO tenant_access_app;

-- 禁用、领取及最终删除后都不可重新成为可公开读取的 ACTIVE 素材。
CREATE OR REPLACE FUNCTION prevent_disabled_brand_asset_reactivation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('DISABLED', 'REMOVING', 'REMOVED') AND NEW.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'Retired brand asset cannot be reactivated';
    END IF;
    IF OLD.disabled_at IS NOT NULL AND
       (NEW.disabled_at IS DISTINCT FROM OLD.disabled_at OR NEW.disabled_by IS DISTINCT FROM OLD.disabled_by) THEN
        RAISE EXCEPTION 'Policy disable metadata is immutable';
    END IF;
    RETURN NEW;
END;
$$;
