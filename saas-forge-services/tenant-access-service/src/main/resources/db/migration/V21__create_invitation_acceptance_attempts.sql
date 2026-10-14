-- The token digest locates a Tenant before the runtime role can set its RLS target.
CREATE FUNCTION locate_invitation_by_token_hash(p_token_hash CHAR(64))
RETURNS TABLE (invitation_id UUID, tenant_id UUID)
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT id, invitations.tenant_id FROM public.invitations
    WHERE invitations.token_hash = p_token_hash
    LIMIT 1
$$;
REVOKE ALL ON FUNCTION locate_invitation_by_token_hash(CHAR(64)) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION locate_invitation_by_token_hash(CHAR(64)) TO tenant_access_app;

CREATE TABLE invitation_acceptance_attempts (
    invitation_id UUID PRIMARY KEY REFERENCES invitations(id),
    tenant_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    consume_operation_id UUID NOT NULL,
    release_operation_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    state TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_invitation_acceptance_tenant FOREIGN KEY (tenant_id, invitation_id)
        REFERENCES invitations(tenant_id, id),
    CONSTRAINT ck_invitation_acceptance_ids CHECK (
        uuid_extract_version(invitation_id) = 7
        AND uuid_extract_version(tenant_id) = 7
        AND uuid_extract_version(identity_id) = 7
        AND uuid_extract_version(idempotency_key) = 7
        AND uuid_extract_version(consume_operation_id) = 7
        AND uuid_extract_version(release_operation_id) = 7
        AND uuid_extract_version(membership_id) = 7),
    CONSTRAINT ck_invitation_acceptance_state CHECK (
        state IN ('PREPARED', 'CONSUMED', 'COMPENSATING', 'COMPENSATED', 'SUCCEEDED'))
);
ALTER TABLE invitation_acceptance_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation_acceptance_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY invitation_acceptance_runtime_access ON invitation_acceptance_attempts
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY invitation_acceptance_migration_access ON invitation_acceptance_attempts
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON invitation_acceptance_attempts TO tenant_access_app;
