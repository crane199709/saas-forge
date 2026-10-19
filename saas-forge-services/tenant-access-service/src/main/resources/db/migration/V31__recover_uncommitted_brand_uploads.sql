-- 在 S3 PUT 前持久登记，事务性删除登记与素材/Outbox 提交同时完成。
CREATE TABLE brand_upload_intents (
    asset_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    object_key VARCHAR(200) NOT NULL UNIQUE,
    state VARCHAR(8) NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'CLEANING')),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp() + INTERVAL '5 minutes',
    CONSTRAINT ck_brand_upload_intent_asset_v7 CHECK (uuid_extract_version(asset_id) = 7)
);
CREATE INDEX ix_brand_upload_intents_recovery ON brand_upload_intents (next_attempt_at, asset_id);

ALTER TABLE brand_upload_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE brand_upload_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY brand_upload_intents_app ON brand_upload_intents FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY brand_upload_intents_migrator ON brand_upload_intents FOR ALL TO tenant_access_migrator
    USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON brand_upload_intents TO tenant_access_app;

-- 超时领取会阻止尚在运行的上传事务提交素材，避免清理与迟到的 PUT 交错。
CREATE FUNCTION claim_next_brand_upload_cleanup(p_now TIMESTAMPTZ)
RETURNS TABLE (asset_id UUID, tenant_id UUID, object_key VARCHAR(200))
LANGUAGE SQL VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    WITH candidate AS (
        SELECT intent.asset_id FROM public.brand_upload_intents AS intent
        WHERE intent.next_attempt_at <= p_now
        ORDER BY intent.next_attempt_at, intent.asset_id
        FOR UPDATE SKIP LOCKED LIMIT 1
    ), claimed AS (
        UPDATE public.brand_upload_intents AS intent
        SET state = 'CLEANING', next_attempt_at = p_now + INTERVAL '5 minutes'
        FROM candidate
        WHERE intent.asset_id = candidate.asset_id
        RETURNING intent.asset_id, intent.tenant_id, intent.object_key
    )
    SELECT claimed.asset_id, claimed.tenant_id, claimed.object_key FROM claimed
$$;
REVOKE ALL ON FUNCTION claim_next_brand_upload_cleanup(TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_next_brand_upload_cleanup(TIMESTAMPTZ) TO tenant_access_app;
