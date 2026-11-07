-- Dead Event 保留原始消息体：非法 JSON 同样必须可靠捕获；tenant_id 只用于上下文恢复，不启用 RLS。
CREATE TABLE forge_dead_event (
    id uuid PRIMARY KEY,
    consumer_group varchar(255) NOT NULL,
    event_id varchar(128) NOT NULL,
    tenant_id uuid,
    original_tenant_id text,
    topic varchar(255) NOT NULL,
    tag varchar(255) NOT NULL,
    payload bytea NOT NULL,
    delivery_attempt integer NOT NULL,
    last_error text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REPLAYED', 'ABANDONED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolution_reason text,
    UNIQUE (consumer_group, event_id)
);
COMMENT ON TABLE forge_dead_event IS '消费重试耗尽的死信事件，留待 Operator 重放或放弃';
COMMENT ON COLUMN forge_dead_event.id IS '死信记录 ID，UUIDv7';
COMMENT ON COLUMN forge_dead_event.consumer_group IS '捕获事件的消费组';
COMMENT ON COLUMN forge_dead_event.event_id IS '事件 ID，外部事件缺少事件 ID 时使用 RocketMQ 消息 ID';
COMMENT ON COLUMN forge_dead_event.tenant_id IS '事件所属租户 ID，无租户或租户头无法解析时为 NULL';
COMMENT ON COLUMN forge_dead_event.original_tenant_id IS '原始租户消息头，重放必须使用此值，非法头不得降级为无租户执行';
COMMENT ON COLUMN forge_dead_event.topic IS '原始 RocketMQ 主题';
COMMENT ON COLUMN forge_dead_event.tag IS '原始 RocketMQ 标签';
COMMENT ON COLUMN forge_dead_event.payload IS '原始消息体字节，包含无法解析的消息体，不对外返回';
COMMENT ON COLUMN forge_dead_event.delivery_attempt IS '捕获时的投递次数';
COMMENT ON COLUMN forge_dead_event.last_error IS '最近一次处理失败的错误信息';
COMMENT ON COLUMN forge_dead_event.status IS '处置状态：PENDING 待处置、REPLAYED 已重放、ABANDONED 已放弃';
COMMENT ON COLUMN forge_dead_event.created_at IS '死信捕获时间';
COMMENT ON COLUMN forge_dead_event.resolved_at IS '处置完成时间，待处置时为 NULL';
COMMENT ON COLUMN forge_dead_event.resolution_reason IS 'Operator 填写的处置原因';
CREATE INDEX forge_dead_event_pending_idx ON forge_dead_event (created_at, id) WHERE status = 'PENDING';
CREATE INDEX forge_dead_event_resolved_idx ON forge_dead_event (resolved_at) WHERE status <> 'PENDING';
