-- Tenant 主数据与各服务的初始化状态（工单 14）。平台数据不属于任何租户，表上不带 tenant_id 隔离与 RLS。
CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    status varchar(20) NOT NULL,
    -- 初始管理员的邮箱或手机号，只用于开通时邀请，之后成员关系以 iam 为准
    admin_contact varchar(254) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE tenant IS '租户主数据：状态机、到期与删除冷静期由 platform 维护';
COMMENT ON COLUMN tenant.id IS '租户 ID，UUIDv7';
COMMENT ON COLUMN tenant.name IS '租户名称';
COMMENT ON COLUMN tenant.status IS '状态：pending、trial、active、suspended、expired、archived 或 deleted';
COMMENT ON COLUMN tenant.admin_contact IS '初始管理员的邮箱或手机号，只用于开通时邀请';
COMMENT ON COLUMN tenant.created_at IS '创建时间';
COMMENT ON COLUMN tenant.updated_at IS '最后更新时间';
COMMENT ON COLUMN tenant.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN tenant.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN tenant.deleted IS '逻辑删除标记，true 表示对常规查询不可见';

-- 每个服务对每个租户一行：创建租户时按配置的服务预建为 pending，服务回报后改为 succeeded 或 failed。
CREATE TABLE tenant_initialization (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    error text,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE tenant_initialization IS '各服务对租户的初始化状态：每个服务对每个租户一行';
COMMENT ON COLUMN tenant_initialization.id IS '主键，UUIDv7';
COMMENT ON COLUMN tenant_initialization.tenant_id IS '租户 ID';
COMMENT ON COLUMN tenant_initialization.service IS '执行初始化的服务';
COMMENT ON COLUMN tenant_initialization.status IS '状态：pending、succeeded 或 failed';
COMMENT ON COLUMN tenant_initialization.error IS '初始化失败时的错误信息';
COMMENT ON COLUMN tenant_initialization.created_at IS '创建时间';
COMMENT ON COLUMN tenant_initialization.updated_at IS '最后更新时间';
COMMENT ON COLUMN tenant_initialization.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN tenant_initialization.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN tenant_initialization.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX tenant_initialization_tenant_service_key ON tenant_initialization (tenant_id, service) WHERE NOT deleted;

-- forge-sdk-mq 需要的表，与 SDK 中 io/saasforge/sdk/mq/schema.sql 一致；不带 RLS，原因见该文件。
CREATE TABLE forge_outbox (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    topic varchar(255) NOT NULL,
    tag varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error text,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE forge_outbox IS '事务发件箱：与业务写入同事务落库，由投递器发送到 RocketMQ';
COMMENT ON COLUMN forge_outbox.id IS '事件 ID，UUIDv7';
COMMENT ON COLUMN forge_outbox.tenant_id IS '事件所属租户 ID，投递时还原消息头，无租户上下文时为 NULL';
COMMENT ON COLUMN forge_outbox.topic IS 'RocketMQ 主题';
COMMENT ON COLUMN forge_outbox.tag IS 'RocketMQ 标签';
COMMENT ON COLUMN forge_outbox.payload IS '消息体 JSON';
COMMENT ON COLUMN forge_outbox.attempts IS '已失败的投递次数';
COMMENT ON COLUMN forge_outbox.next_attempt_at IS '下次投递时间';
COMMENT ON COLUMN forge_outbox.sent_at IS '发送成功时间，未发送时为 NULL';
COMMENT ON COLUMN forge_outbox.last_error IS '最近一次投递失败的错误信息';
COMMENT ON COLUMN forge_outbox.created_at IS '事件创建时间';
CREATE INDEX forge_outbox_due_idx ON forge_outbox (next_attempt_at) WHERE sent_at IS NULL;

CREATE TABLE forge_consumed_message (
    consumer_group varchar(255) NOT NULL,
    message_id varchar(128) NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_group, message_id)
);
COMMENT ON TABLE forge_consumed_message IS '已消费消息记录：按消费组与消息 ID 去重，保证消费幂等';
COMMENT ON COLUMN forge_consumed_message.consumer_group IS '消费组';
COMMENT ON COLUMN forge_consumed_message.message_id IS '消息 ID';
COMMENT ON COLUMN forge_consumed_message.consumed_at IS '消费时间';
