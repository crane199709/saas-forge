ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_action;
ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_action CHECK (
    action IN ('SESSION_STARTED', 'TENANT_CREATED', 'TENANT_CONTEXT_SWITCHED',
               'PROJECT_CREATED', 'PROJECT_UPDATED', 'PROJECT_DELETED', 'TASK_CREATED', 'TASK_UPDATED', 'TASK_DELETED')
);
ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_resource_type;
ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_resource_type CHECK (
    resource_type IN ('REFRESH_TOKEN_FAMILY', 'TENANT', 'PROJECT', 'TASK')
);
