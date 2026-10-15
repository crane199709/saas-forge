CREATE TABLE iam_platform_admin_revoke_requests (
    request_id UUID PRIMARY KEY CHECK (uuid_extract_version(request_id) = 7),
    actor_identity_id UUID NOT NULL REFERENCES iam_identities (id),
    target_identity_id UUID NOT NULL REFERENCES iam_identities (id),
    role_assignment_id UUID REFERENCES iam_platform_role_assignments (id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK (completed_at IS NULL OR role_assignment_id IS NOT NULL)
);

CREATE INDEX ix_iam_platform_admin_revoke_pending
    ON iam_platform_admin_revoke_requests (created_at, request_id)
    WHERE role_assignment_id IS NOT NULL AND completed_at IS NULL;

CREATE INDEX ix_iam_platform_issuances_identity_expiry
    ON iam_access_token_issuances (identity_id, expires_at, jti)
    WHERE membership_id IS NULL AND tenant_id IS NULL;

GRANT SELECT, INSERT, UPDATE ON iam_platform_admin_revoke_requests TO iam_app;
