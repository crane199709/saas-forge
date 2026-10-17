DO $$
DECLARE
    existing_check text;
    existing_resource_check text;
BEGIN
    SELECT pg_get_expr(conbin, conrelid) INTO existing_check
    FROM pg_constraint
    WHERE conrelid = 'audit_records'::regclass AND conname = 'ck_audit_records_action';
    IF existing_check IS NULL THEN
        RAISE EXCEPTION 'Expected audit action constraint is missing';
    END IF;
    SELECT pg_get_expr(conbin, conrelid) INTO existing_resource_check
    FROM pg_constraint
    WHERE conrelid = 'audit_records'::regclass AND conname = 'ck_audit_records_resource_type';
    IF existing_resource_check IS NULL THEN
        RAISE EXCEPTION 'Expected audit resource type constraint is missing';
    END IF;
    EXECUTE 'ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_action';
    EXECUTE format(
        'ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_action CHECK ((%s) OR action IN (%L, %L, %L))',
        existing_check, 'INVITATION_CREATED', 'INVITATION_RESENT', 'INVITATION_REVOKED');
    EXECUTE 'ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_resource_type';
    EXECUTE format(
        'ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_resource_type CHECK ((%s) OR resource_type = %L)',
        existing_resource_check, 'INVITATION');
END $$;
