-- Provisioning（工单 14）：初始管理员的邮箱或手机号尚无 Identity 时，先建待激活（pending）的 Identity，
-- 接受邀请时再设置账号与密码。只有 active 的 Identity 必须带凭证；登录查询本就只认 active。
ALTER TABLE identity ALTER COLUMN account DROP NOT NULL;
ALTER TABLE identity ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE identity ADD CONSTRAINT identity_active_credentials
    CHECK (status <> 'active' OR (account IS NOT NULL AND password_hash IS NOT NULL));

-- forge-sdk-mq 需要的表，与 SDK 中 io/saasforge/sdk/mq/schema.sql 一致；不带 RLS，原因见该文件。
CREATE TABLE forge_outbox (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    topic varchar(255) NOT NULL,
    tag varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX forge_outbox_due_idx ON forge_outbox (next_attempt_at) WHERE sent_at IS NULL;

CREATE TABLE forge_consumed_message (
    consumer_group varchar(255) NOT NULL,
    message_id varchar(128) NOT NULL,
    consumed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, message_id)
);
