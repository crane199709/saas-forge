DO $$
DECLARE prior TEXT;
BEGIN
    SELECT pg_get_expr(conbin, conrelid) INTO prior FROM pg_constraint
    WHERE conrelid = 'audit_records'::regclass AND conname = 'ck_audit_records_action';
    IF prior IS NULL THEN RAISE EXCEPTION 'Expected audit action constraint is missing'; END IF;
    ALTER TABLE audit_records DROP CONSTRAINT ck_audit_records_action;
    EXECUTE format('ALTER TABLE audit_records ADD CONSTRAINT ck_audit_records_action CHECK ((%s) OR action = %L)',
        prior, 'INVITATION_ACCEPTED');
END $$;
