CREATE TABLE membership_enable_workflows (
    operation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    actor_identity_id UUID NOT NULL,
    disable_operation_id UUID NOT NULL REFERENCES membership_disable_workflows (operation_id),
    revocation_request_id UUID NOT NULL,
    consume_operation_id UUID NOT NULL UNIQUE,
    compensation_operation_id UUID NOT NULL UNIQUE,
    fence_release_request_id UUID NOT NULL UNIQUE,
    workflow_status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_failure TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_membership_enable_target FOREIGN KEY (tenant_id, membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_membership_enable_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT ck_membership_enable_ids CHECK (
        uuid_extract_version(operation_id) = 7 AND uuid_extract_version(actor_identity_id) = 7
        AND uuid_extract_version(revocation_request_id) = 7
        AND uuid_extract_version(consume_operation_id) = 7
        AND uuid_extract_version(compensation_operation_id) = 7
        AND uuid_extract_version(fence_release_request_id) = 7),
    CONSTRAINT ck_membership_enable_status CHECK (workflow_status IN
        ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING', 'COMPLETED', 'QUOTA_REJECTED', 'COMPENSATED'))
);

CREATE UNIQUE INDEX uq_active_membership_enable ON membership_enable_workflows (tenant_id, membership_id)
    WHERE workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING');
CREATE INDEX ix_membership_enable_recovery ON membership_enable_workflows (next_attempt_at, operation_id)
    WHERE workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING');

ALTER TABLE membership_enable_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_enable_workflows FORCE ROW LEVEL SECURITY;
CREATE POLICY membership_enable_runtime_access ON membership_enable_workflows
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY membership_enable_migration_access ON membership_enable_workflows
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON membership_enable_workflows TO tenant_access_app;

-- Worker 只领取持久待办；领取过程按单行租约串行化，应用账号仍须设置 Tenant RLS 目标。
CREATE FUNCTION claim_next_membership_enable(p_now TIMESTAMPTZ, p_lease_until TIMESTAMPTZ)
RETURNS TABLE (tenant_id UUID, operation_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE selected RECORD;
BEGIN
    SELECT work.tenant_id, work.operation_id INTO selected
    FROM public.membership_enable_workflows work
    WHERE work.workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING')
      AND work.next_attempt_at <= p_now
      AND (work.lease_until IS NULL OR work.lease_until <= p_now)
    ORDER BY work.next_attempt_at, work.operation_id
    FOR UPDATE SKIP LOCKED LIMIT 1;
    IF NOT FOUND THEN RETURN; END IF;
    UPDATE public.membership_enable_workflows work
    SET lease_until = p_lease_until, attempt_count = attempt_count + 1, updated_at = p_now
    WHERE work.operation_id = selected.operation_id;
    tenant_id := selected.tenant_id;
    operation_id := selected.operation_id;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION claim_next_membership_enable(TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_next_membership_enable(TIMESTAMPTZ, TIMESTAMPTZ) TO tenant_access_app;

-- 会员已写为 ENABLED 但旧 Fence 尚未精确解除时，所有候选和授权仍失败关闭。
CREATE OR REPLACE FUNCTION validate_membership(p_identity_id UUID, p_membership_id UUID)
RETURNS TABLE (tenant_id UUID)
LANGUAGE SQL STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$
    SELECT tenant.id
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    WHERE membership.id = p_membership_id AND membership.identity_id = p_identity_id
      AND membership.membership_status = 'ENABLED' AND tenant.tenant_status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_lifecycle_workflows AS closure
          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
      AND NOT EXISTS (
          SELECT 1 FROM public.membership_disable_workflows AS disabling
          WHERE disabling.tenant_id = membership.tenant_id AND disabling.membership_id = membership.id
            AND disabling.workflow_status IN ('REVOKING', 'RECOVERY_REQUIRED', 'RELEASE_PENDING'))
      AND NOT EXISTS (
          SELECT 1 FROM public.membership_enable_workflows AS enabling
          WHERE enabling.tenant_id = membership.tenant_id AND enabling.membership_id = membership.id
            AND enabling.workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING'))
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
$$;

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
      AND NOT EXISTS (
          SELECT 1 FROM public.membership_enable_workflows AS enabling
          WHERE enabling.tenant_id = membership.tenant_id AND enabling.membership_id = membership.id
            AND enabling.workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING'))
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
    ORDER BY tenant.display_name COLLATE "C", membership.id LIMIT 101
$$;
