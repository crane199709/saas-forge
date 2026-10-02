-- 已保存的事实内容和来源不可由运行身份改写，只允许维护投递状态。
REVOKE UPDATE ON project_outbox_events FROM project_app;
GRANT UPDATE (published_at, claim_token, claimed_until, attempt_count, next_attempt_at, last_failure)
    ON project_outbox_events TO project_app;
