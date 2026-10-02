-- Outbox 是服务内部投递控制表，不通过业务 API 暴露；只保存已审查的事实白名单。
CREATE TABLE project_outbox_events (
    event_id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    topic varchar(200) NOT NULL,
    ordering_key uuid NOT NULL,
    event_snapshot jsonb NOT NULL,
    traceparent varchar(55),
    published_at timestamptz,
    claim_token uuid,
    claimed_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    last_failure varchar(200),
    CHECK ((claim_token IS NULL) = (claimed_until IS NULL))
);
CREATE INDEX project_outbox_pending ON project_outbox_events (next_attempt_at, occurred_at, event_id)
    WHERE published_at IS NULL;
GRANT SELECT, INSERT, UPDATE ON project_outbox_events TO project_app;
