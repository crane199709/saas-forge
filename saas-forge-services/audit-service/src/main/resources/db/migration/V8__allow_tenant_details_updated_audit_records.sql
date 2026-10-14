DO $$
DECLARE
    existing_check text;
BEGIN
    SELECT pg_get_expr(conbin, conrelid) INTO existing_check
    FROM pg_constraint
    WHERE conrelid = 'audit_records'::regclass AND conname = 'ck_audit_records_action';

    IF existing_check IS NULL THEN
        RAISE EXCEPTION 'Expected audit action constraint is missing';
    END IF;

    EXECUTE 'ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_action';
    EXECUTE format('ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_action CHECK ((%s) OR action = %L)',
                   existing_check, 'TENANT_DETAILS_UPDATED');
END $$;
