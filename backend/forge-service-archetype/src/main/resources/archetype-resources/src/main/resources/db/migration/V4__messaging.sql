#set( $dollar = '$' )
#set( $hash = '#' )
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

-- 示例消费方的业务表：消费「订单已创建」后写入，与 orders 一样按租户隔离。
CREATE TABLE order_notifications (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    title varchar(255) NOT NULL
);
CREATE INDEX order_notifications_tenant_id_idx ON order_notifications (tenant_id);

ALTER TABLE order_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY order_notifications_tenant_isolation ON order_notifications
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
