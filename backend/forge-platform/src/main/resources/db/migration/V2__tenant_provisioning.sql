-- Tenant 主数据与各服务的初始化状态（工单 14）。平台数据不属于任何租户，表上不带 tenant_id 隔离与 RLS。
CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    status varchar(20) NOT NULL,
    -- 初始管理员的邮箱或手机号，只用于开通时邀请，之后成员关系以 iam 为准
    admin_contact varchar(254) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);

-- 每个服务对每个租户一行：创建租户时按配置的服务预建为 pending，服务回报后改为 succeeded 或 failed。
CREATE TABLE tenant_initialization (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX tenant_initialization_tenant_service_key ON tenant_initialization (tenant_id, service) WHERE NOT deleted;

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
