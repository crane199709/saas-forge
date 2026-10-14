CREATE TABLE membership_disable_workflows (
    operation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    actor_identity_id UUID NOT NULL,
    revocation_request_id UUID NOT NULL UNIQUE,
    release_operation_id UUID NOT NULL UNIQUE,
    workflow_status TEXT NOT NULL,
    revoked_family_count BIGINT NOT NULL DEFAULT 0,
    revoked_jti_count BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_failure TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_membership_disable_target FOREIGN KEY (tenant_id, membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_membership_disable_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT ck_membership_disable_ids CHECK (
        uuid_extract_version(operation_id) = 7 AND uuid_extract_version(revocation_request_id) = 7
        AND uuid_extract_version(release_operation_id) = 7 AND uuid_extract_version(actor_identity_id) = 7),
    CONSTRAINT ck_membership_disable_status CHECK (
        workflow_status IN ('REVOKING', 'RECOVERY_REQUIRED', 'RELEASE_PENDING', 'COMPLETED')),
    CONSTRAINT ck_membership_disable_counts CHECK (revoked_family_count >= 0 AND revoked_jti_count >= 0)
);

CREATE UNIQUE INDEX uq_active_membership_disable ON membership_disable_workflows (tenant_id, membership_id)
    WHERE workflow_status <> 'COMPLETED';
CREATE INDEX ix_membership_disable_recovery ON membership_disable_workflows (next_attempt_at, operation_id)
    WHERE workflow_status IN ('REVOKING', 'RELEASE_PENDING');

ALTER TABLE membership_disable_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_disable_workflows FORCE ROW LEVEL SECURITY;
CREATE POLICY membership_disable_runtime_access ON membership_disable_workflows
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY membership_disable_migration_access ON membership_disable_workflows
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON membership_disable_workflows TO tenant_access_app;

-- Worker 在受限账号下领取任务；函数只返回可领取的 Tenant 与工作流，并在事务内设置 RLS 目标。
CREATE FUNCTION claim_next_membership_disable(p_now TIMESTAMPTZ, p_lease_until TIMESTAMPTZ)
RETURNS TABLE (tenant_id UUID, operation_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE selected RECORD;
BEGIN
    SELECT work.tenant_id, work.operation_id INTO selected
    FROM public.membership_disable_workflows work
    WHERE work.workflow_status IN ('REVOKING', 'RELEASE_PENDING')
      AND work.next_attempt_at <= p_now
      AND (work.lease_until IS NULL OR work.lease_until <= p_now)
    ORDER BY work.next_attempt_at, work.operation_id
    FOR UPDATE SKIP LOCKED LIMIT 1;
    IF NOT FOUND THEN RETURN; END IF;
    UPDATE public.membership_disable_workflows work
    SET lease_until = p_lease_until, attempt_count = attempt_count + 1, updated_at = p_now
    WHERE work.operation_id = selected.operation_id;
    tenant_id := selected.tenant_id;
    operation_id := selected.operation_id;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION claim_next_membership_disable(TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_next_membership_disable(TIMESTAMPTZ, TIMESTAMPTZ) TO tenant_access_app;
