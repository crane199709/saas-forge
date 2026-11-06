-- forge-sdk-mq 需要的表。服务把本文件内容复制到自己的 Flyway 迁移中执行，SDK 不自带迁移，
-- 以免与服务的迁移版本号交错；表结构变更时 SDK 会在发布说明中给出升级语句。
-- 两张表都不带 RLS：投递器与去重没有租户上下文，需要跨租户访问；tenant_id 只用于投递时还原消息头。

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
-- 投递器只扫描未发送的事件，部分索引让已发送的历史行不拖慢扫描
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
