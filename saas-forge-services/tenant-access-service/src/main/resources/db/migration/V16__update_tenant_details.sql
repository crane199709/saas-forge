CREATE TABLE tenant_detail_updates (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    display_name VARCHAR(200),
    tenant_status VARCHAR(20),
    expires_at TIMESTAMPTZ,
    tenant_created_at TIMESTAMPTZ,
    tenant_updated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, actor_identity_id, idempotency_key),
    CONSTRAINT ck_tenant_detail_update_actor_uuidv7 CHECK (uuid_extract_version(actor_identity_id) = 7),
    CONSTRAINT ck_tenant_detail_update_key_uuidv7 CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_tenant_detail_update_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_detail_update_completed CHECK (
        (completed_at IS NULL AND display_name IS NULL AND tenant_status IS NULL
            AND tenant_created_at IS NULL AND tenant_updated_at IS NULL)
        OR (completed_at IS NOT NULL AND display_name IS NOT NULL AND tenant_status IS NOT NULL
            AND tenant_created_at IS NOT NULL AND tenant_updated_at IS NOT NULL))
);

ALTER TABLE tenant_detail_updates ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_detail_updates FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_detail_updates_runtime_access ON tenant_detail_updates
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_detail_updates_migration_access ON tenant_detail_updates
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON tenant_detail_updates TO tenant_access_app;
