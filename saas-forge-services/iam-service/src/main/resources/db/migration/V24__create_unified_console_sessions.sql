ALTER TABLE iam_refresh_token_families
    ADD COLUMN session_protocol TEXT NOT NULL DEFAULT 'LEGACY_V1',
    ADD CONSTRAINT ck_iam_refresh_token_families_protocol
        CHECK (session_protocol IN ('LEGACY_V1', 'CONSOLE_V2'));

CREATE TABLE iam_console_slots (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    locator_digest BYTEA NOT NULL UNIQUE CHECK (octet_length(locator_digest) = 32),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    family_id UUID UNIQUE REFERENCES iam_refresh_token_families (id),
    transition TEXT NOT NULL DEFAULT 'NONE'
        CHECK (transition IN ('NONE', 'SWITCH_PENDING', 'CONTEXT_REFRESH_REQUIRED', 'ENDING')),
    CHECK (family_id IS NOT NULL OR transition = 'NONE')
);

CREATE TABLE iam_console_operations (
    slot_id UUID NOT NULL REFERENCES iam_console_slots (id),
    operation_key UUID NOT NULL CHECK (uuid_extract_version(operation_key) = 7),
    family_id UUID REFERENCES iam_refresh_token_families (id),
    kind TEXT NOT NULL CHECK (kind IN ('LOGOUT', 'SELECT_CONTEXT', 'PASSWORD_CHANGE')),
    fingerprint TEXT NOT NULL,
    result_revision BIGINT CHECK (result_revision >= 0),
    PRIMARY KEY (slot_id, operation_key)
);
CREATE INDEX ix_iam_console_operations_family ON iam_console_operations (family_id);

GRANT SELECT, INSERT, UPDATE ON iam_console_slots, iam_console_operations TO iam_app;

-- 协议退役为单向状态；旧实例也必须经过这一持久签发门禁。
CREATE TABLE iam_console_protocol (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    legacy_blocked BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO iam_console_protocol DEFAULT VALUES;
ALTER TABLE iam_refresh_token_families ADD COLUMN console_retired_at TIMESTAMPTZ;
CREATE INDEX ix_iam_console_pending_legacy ON iam_refresh_token_families (id)
    WHERE session_protocol = 'LEGACY_V1' AND console_retired_at IS NULL;
GRANT SELECT, UPDATE ON iam_console_protocol TO iam_app;

CREATE FUNCTION guard_legacy_session_issuance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    blocked BOOLEAN;
    protocol TEXT;
BEGIN
    SELECT legacy_blocked INTO blocked FROM iam_console_protocol WHERE singleton FOR SHARE;
    IF TG_TABLE_NAME = 'iam_refresh_token_families' THEN
        protocol := NEW.session_protocol;
    ELSE
        SELECT session_protocol INTO protocol FROM iam_refresh_token_families WHERE id = NEW.family_id;
    END IF;
    IF blocked AND protocol = 'LEGACY_V1' THEN
        RAISE EXCEPTION 'AUTH_PROTOCOL_RETIRED' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_legacy_family BEFORE INSERT ON iam_refresh_token_families
    FOR EACH ROW EXECUTE FUNCTION guard_legacy_session_issuance();
CREATE TRIGGER guard_legacy_refresh BEFORE INSERT ON iam_refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION guard_legacy_session_issuance();
CREATE TRIGGER guard_legacy_access BEFORE INSERT ON iam_access_token_issuances
    FOR EACH ROW EXECUTE FUNCTION guard_legacy_session_issuance();

CREATE FUNCTION prevent_console_protocol_rollback() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.legacy_blocked AND NOT NEW.legacy_blocked THEN
        RAISE EXCEPTION 'Console protocol retirement cannot be rolled back' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER prevent_console_protocol_rollback BEFORE UPDATE ON iam_console_protocol
    FOR EACH ROW EXECUTE FUNCTION prevent_console_protocol_rollback();
