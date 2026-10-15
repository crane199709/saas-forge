DO $$
DECLARE previous_action_check TEXT;
BEGIN
    SELECT pg_get_expr(conbin, conrelid) INTO previous_action_check
    FROM pg_constraint WHERE conrelid = 'audit_records'::regclass
        AND conname = 'ck_audit_records_action';
    IF previous_action_check IS NULL THEN
        RAISE EXCEPTION 'Audit action constraint is required before V25';
    END IF;
    ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_action;
    EXECUTE format('ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_action '
                   || 'CHECK ((%s) OR action = %L)', previous_action_check, 'BRAND_ASSET_REMOVED');
END $$;
