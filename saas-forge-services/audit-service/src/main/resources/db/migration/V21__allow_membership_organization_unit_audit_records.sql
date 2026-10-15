DO $$
DECLARE
    previous_action_check TEXT;
    previous_resource_check TEXT;
BEGIN
    SELECT pg_get_expr(conbin, conrelid) INTO previous_action_check
    FROM pg_constraint WHERE conrelid = 'audit_records'::regclass
        AND conname = 'ck_audit_records_action';
    SELECT pg_get_expr(conbin, conrelid) INTO previous_resource_check
    FROM pg_constraint WHERE conrelid = 'audit_records'::regclass
        AND conname = 'ck_audit_records_resource_type';
    IF previous_action_check IS NULL OR previous_resource_check IS NULL THEN
        RAISE EXCEPTION 'Audit action/resource_type constraints are required before V21';
    END IF;

    ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_action;
    EXECUTE format('ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_action '
                   || 'CHECK ((%s) OR action IN (%L, %L))', previous_action_check,
                   'MEMBERSHIP_ORGANIZATION_UNIT_ADDED', 'MEMBERSHIP_ORGANIZATION_UNIT_REMOVED');

    ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_resource_type;
    EXECUTE format('ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_resource_type '
                   || 'CHECK ((%s) OR resource_type = %L)', previous_resource_check, 'MEMBERSHIP');
END $$;
