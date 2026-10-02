-- 原有授权组合保持，CI 与 Remote Delivery 身份使用独立且精确的 Scope 组合。
ALTER TABLE iam_oauth_clients
    DROP CONSTRAINT ck_iam_oauth_clients_type,
    DROP CONSTRAINT ck_iam_oauth_clients_type_key,
    DROP CONSTRAINT ck_iam_oauth_clients_scopes,
    ADD CONSTRAINT ck_iam_oauth_clients_type CHECK (client_type IN ('RUNTIME_SERVICE', 'RESERVED_SERVICE', 'CI_CLIENT')),
    ADD CONSTRAINT ck_iam_oauth_clients_type_key CHECK (
        (client_type IN ('RUNTIME_SERVICE', 'CI_CLIENT') AND reserved_service_key IS NULL)
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key IN ('IAM', 'TENANT_ACCESS', 'ENTITLEMENT', 'REMOTE_DELIVERY'))
    ),
ADD CONSTRAINT ck_iam_oauth_clients_scopes CHECK (
        array_position(allowed_scopes, NULL) IS NULL AND (
        (client_type = 'RUNTIME_SERVICE'
            AND cardinality(allowed_scopes) BETWEEN 1 AND 2
            AND allowed_scopes <@ ARRAY['runtime:read', 'runtime:quota:write']::TEXT[]
            AND cardinality(array_positions(allowed_scopes, 'runtime:read')) <= 1
            AND cardinality(array_positions(allowed_scopes, 'runtime:quota:write')) <= 1)
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'IAM'
            AND cardinality(allowed_scopes) = 1
            AND allowed_scopes @> ARRAY['tenant-access:membership:read']::TEXT[]
            AND allowed_scopes <@ ARRAY['tenant-access:membership:read']::TEXT[])
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'TENANT_ACCESS'
            AND cardinality(allowed_scopes) = 5
            AND allowed_scopes @> ARRAY['iam:identity:write', 'iam:password-setup:write',
                'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[]
            AND allowed_scopes <@ ARRAY['iam:identity:write', 'iam:password-setup:write',
                'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[])
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'ENTITLEMENT'
            AND cardinality(allowed_scopes) = 2
            AND allowed_scopes @> ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[]
            AND allowed_scopes <@ ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[])
        OR (client_type = 'CI_CLIENT' AND allowed_scopes = ARRAY['remote-delivery:manifest:register']::TEXT[])
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'REMOTE_DELIVERY'
            AND cardinality(allowed_scopes) = 3
            AND allowed_scopes @> ARRAY['iam:platform-role:read', 'tenant-access:membership:read', 'tenant-access:tenant:read']::TEXT[]
            AND allowed_scopes <@ ARRAY['iam:platform-role:read', 'tenant-access:membership:read', 'tenant-access:tenant:read']::TEXT[])
        )
    );
