ALTER TABLE quota_operations DROP CONSTRAINT ck_quota_operation_purpose;
ALTER TABLE quota_operations ADD CONSTRAINT ck_quota_operation_purpose
    CHECK (purpose IN ('TENANT_ADMIN_INITIALIZATION', 'INVITATION_ACCEPTANCE'));
