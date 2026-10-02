CREATE TABLE remote_manifests (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    module TEXT NOT NULL,
    version TEXT NOT NULL,
    source TEXT NOT NULL,
    ui_version TEXT NOT NULL,
    entry_sha256 TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING_REVIEW',
    registered_by UUID NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    enabled_by UUID,
    enabled_at TIMESTAMPTZ,
    CONSTRAINT uq_remote_manifest_version UNIQUE (module, version),
    CONSTRAINT ck_remote_manifest_ids CHECK (uuid_extract_version(id) = 7 AND uuid_extract_version(registered_by) = 7),
    CONSTRAINT ck_remote_manifest_declaration CHECK (
        module ~ '^[a-z][a-z0-9-]{1,62}$'
        AND version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
        AND entry_sha256 ~ '^[0-9a-f]{64}$'
        AND length(source) BETWEEN 1 AND 512 AND length(ui_version) BETWEEN 1 AND 100),
    CONSTRAINT ck_remote_manifest_state CHECK (state IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'ENABLED')),
    CONSTRAINT ck_remote_manifest_review CHECK (
        (state = 'PENDING_REVIEW' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (state <> 'PENDING_REVIEW' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND uuid_extract_version(reviewed_by) = 7 AND reviewed_at >= registered_at)),
    CONSTRAINT ck_remote_manifest_enable CHECK (
        (state <> 'ENABLED' AND enabled_by IS NULL AND enabled_at IS NULL)
        OR (state = 'ENABLED' AND enabled_by IS NOT NULL AND enabled_at IS NOT NULL
            AND uuid_extract_version(enabled_by) = 7 AND enabled_at >= reviewed_at))
);
CREATE UNIQUE INDEX uq_remote_manifest_enabled ON remote_manifests (module) WHERE state = 'ENABLED';
CREATE TABLE remote_manifest_facts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    manifest_id UUID NOT NULL REFERENCES remote_manifests(id),
    actor_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('REGISTER', 'APPROVE', 'REJECT', 'ENABLE')),
    occurred_at TIMESTAMPTZ NOT NULL,
    idempotency_key UUID,
    result JSONB NOT NULL,
    CHECK (uuid_extract_version(id) = 7 AND uuid_extract_version(actor_id) = 7),
    CHECK (idempotency_key IS NULL OR uuid_extract_version(idempotency_key) = 7),
    UNIQUE (actor_id, idempotency_key),
    UNIQUE (manifest_id, action)
);
GRANT SELECT, INSERT ON remote_manifests, remote_manifest_facts TO remote_delivery_app;
GRANT UPDATE (state, reviewed_by, reviewed_at, enabled_by, enabled_at) ON remote_manifests TO remote_delivery_app;
