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

-- 示例消费方的业务表：消费「订单已创建」后写入，与 orders 一样按租户隔离。
CREATE TABLE order_notifications (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    title varchar(255) NOT NULL
);
COMMENT ON TABLE order_notifications IS '示例订单通知：消费「订单已创建」事件后写入';
COMMENT ON COLUMN order_notifications.id IS '通知 ID，UUIDv7';
COMMENT ON COLUMN order_notifications.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN order_notifications.order_id IS '关联的订单 ID';
COMMENT ON COLUMN order_notifications.title IS '通知标题';
CREATE INDEX order_notifications_tenant_id_idx ON order_notifications (tenant_id);

ALTER TABLE order_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY order_notifications_tenant_isolation ON order_notifications
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
