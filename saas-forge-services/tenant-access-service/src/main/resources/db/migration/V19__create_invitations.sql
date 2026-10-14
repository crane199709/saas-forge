CREATE TABLE invitations (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    identity_id UUID NOT NULL,
    email TEXT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_generation INTEGER NOT NULL DEFAULT 1,
    invitation_status TEXT NOT NULL DEFAULT 'PENDING',
    delivery_status TEXT NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    created_by_identity_id UUID NOT NULL,
    CONSTRAINT uq_invitations_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_invitations_uuidv7 CHECK (
        uuid_extract_version(id) = 7 AND uuid_extract_version(tenant_id) = 7
        AND uuid_extract_version(identity_id) = 7
        AND uuid_extract_version(created_by_identity_id) = 7),
    CONSTRAINT ck_invitations_email CHECK (email = lower(btrim(email)) AND char_length(email) BETWEEN 3 AND 320),
    CONSTRAINT ck_invitations_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_invitations_generation CHECK (token_generation > 0),
    CONSTRAINT ck_invitations_status CHECK (invitation_status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_invitations_delivery CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_invitations_expiry CHECK (expires_at = created_at + interval '7 days')
);

CREATE INDEX ix_invitations_pending_email ON invitations (tenant_id, email, expires_at)
    WHERE invitation_status = 'PENDING';

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY invitations_runtime_access ON invitations
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY invitations_migration_access ON invitations
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON invitations TO tenant_access_app;
