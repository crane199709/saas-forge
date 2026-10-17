CREATE TABLE iam_platform_admin_grant_requests (
    request_id UUID PRIMARY KEY,
    actor_identity_id UUID NOT NULL REFERENCES iam_identities (id),
    email TEXT NOT NULL,
    identity_id UUID REFERENCES iam_identities (id),
    role_assignment_id UUID REFERENCES iam_platform_role_assignments (id),
    credential_status TEXT,
    newly_granted BOOLEAN,
    setup_caller_client_id UUID REFERENCES iam_oauth_clients (id),
    setup_state TEXT,
    delivery_lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_platform_admin_grant_request_uuidv7 CHECK (uuid_extract_version(request_id) = 7),
    CONSTRAINT ck_iam_platform_admin_grant_setup_state CHECK (
        setup_state IS NULL OR setup_state IN ('DELIVERED', 'PASSWORD_READY', 'RECOVERY_REQUIRED')),
    CONSTRAINT ck_iam_platform_admin_grant_setup_caller CHECK (
        (credential_status = 'SETUP_ALLOWED' AND newly_granted = TRUE) =
            (setup_caller_client_id IS NOT NULL)),
    CONSTRAINT ck_iam_platform_admin_grant_request_complete CHECK (
        (identity_id IS NULL AND role_assignment_id IS NULL AND credential_status IS NULL AND newly_granted IS NULL)
        OR (identity_id IS NOT NULL AND role_assignment_id IS NOT NULL AND credential_status IN
            ('SETUP_ALLOWED', 'PASSWORD_READY', 'RECOVERY_REQUIRED') AND newly_granted IS NOT NULL)
    )
);

GRANT SELECT, INSERT, UPDATE ON iam_platform_admin_grant_requests TO iam_app;
