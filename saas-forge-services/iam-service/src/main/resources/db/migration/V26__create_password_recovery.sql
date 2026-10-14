ALTER TABLE iam_revocation_fences ADD COLUMN identity_id UUID;
ALTER TABLE iam_revocation_fences ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE iam_revocation_fences DROP CONSTRAINT ck_iam_revocation_fences_target_uuidv7;
ALTER TABLE iam_revocation_fences ADD CONSTRAINT ck_iam_revocation_fences_target_uuidv7
    CHECK (uuid_extract_version(target_id) = 7
        AND (tenant_id IS NULL OR uuid_extract_version(tenant_id) = 7)
        AND (membership_id IS NULL OR uuid_extract_version(membership_id) = 7)
        AND (identity_id IS NULL OR uuid_extract_version(identity_id) = 7));
ALTER TABLE iam_revocation_fences DROP CONSTRAINT ck_iam_revocation_fences_target_shape;
ALTER TABLE iam_revocation_fences ADD CONSTRAINT ck_iam_revocation_fences_target_shape CHECK (
    (target_type = 'TENANT' AND membership_id IS NULL AND identity_id IS NULL AND target_id = tenant_id)
    OR (target_type = 'MEMBERSHIP' AND membership_id IS NOT NULL AND identity_id IS NULL AND target_id = membership_id)
    OR (target_type = 'IDENTITY' AND tenant_id IS NULL AND membership_id IS NULL AND target_id = identity_id)
);
CREATE INDEX ix_iam_revocation_fences_active_identity ON iam_revocation_fences (identity_id)
    WHERE target_type = 'IDENTITY' AND fence_status = 'ACTIVE';

ALTER TABLE iam_user_session_fence_releases DROP CONSTRAINT ck_iam_user_session_fence_releases_target;
ALTER TABLE iam_user_session_fence_releases ADD CONSTRAINT ck_iam_user_session_fence_releases_target
    CHECK (target_type IN ('TENANT', 'MEMBERSHIP', 'IDENTITY') AND uuid_extract_version(target_id) = 7);

CREATE TABLE iam_password_recovery_challenges (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    token_digest BYTEA NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    invalidated_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    credential_id UUID REFERENCES iam_credentials (id),
    CONSTRAINT ck_iam_password_recovery_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_iam_password_recovery_digest CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_iam_password_recovery_lifetime CHECK (expires_at = issued_at + INTERVAL '30 minutes'),
    CONSTRAINT ck_iam_password_recovery_state CHECK (
        (started_at IS NULL AND completed_at IS NULL AND credential_id IS NULL)
        OR (started_at IS NOT NULL AND credential_id IS NOT NULL
            AND (completed_at IS NULL OR completed_at >= started_at))
    )
);
CREATE UNIQUE INDEX uq_iam_password_recovery_open ON iam_password_recovery_challenges (identity_id)
    WHERE invalidated_at IS NULL AND started_at IS NULL;
GRANT SELECT, INSERT, UPDATE ON iam_password_recovery_challenges TO iam_app;
