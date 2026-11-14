CREATE TABLE tenant_ai_usage_daily (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    usage_date date NOT NULL,
    business_zone varchar(80) NOT NULL,
    snapshot_version bigint NOT NULL CHECK (snapshot_version > 0),
    input_tokens bigint NOT NULL CHECK (input_tokens >= 0),
    output_tokens bigint NOT NULL CHECK (output_tokens >= 0),
    cached_input_tokens bigint NOT NULL CHECK (cached_input_tokens >= 0 AND cached_input_tokens <= input_tokens),
    estimated_tokens bigint NOT NULL CHECK (estimated_tokens >= 0),
    turn_count bigint NOT NULL CHECK (turn_count >= 0),
    tool_calls bigint NOT NULL CHECK (tool_calls >= 0),
    unknown_usage_turns bigint NOT NULL CHECK (unknown_usage_turns >= 0 AND unknown_usage_turns <= turn_count),
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE tenant_ai_usage_daily IS 'AI 服务回报的租户每日累计用量投影';
COMMENT ON COLUMN tenant_ai_usage_daily.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN tenant_ai_usage_daily.tenant_id IS '真实平台租户 ID';
COMMENT ON COLUMN tenant_ai_usage_daily.usage_date IS '计量业务日期';
COMMENT ON COLUMN tenant_ai_usage_daily.business_zone IS '全平台一致的业务时区';
COMMENT ON COLUMN tenant_ai_usage_daily.snapshot_version IS '累计快照版本';
COMMENT ON COLUMN tenant_ai_usage_daily.input_tokens IS '已知输入量，包含缓存';
COMMENT ON COLUMN tenant_ai_usage_daily.output_tokens IS '已知输出量';
COMMENT ON COLUMN tenant_ai_usage_daily.cached_input_tokens IS '输入中的缓存子集';
COMMENT ON COLUMN tenant_ai_usage_daily.estimated_tokens IS '未知用量的估计占额';
COMMENT ON COLUMN tenant_ai_usage_daily.turn_count IS '已结算回合数';
COMMENT ON COLUMN tenant_ai_usage_daily.tool_calls IS '工具调用次数';
COMMENT ON COLUMN tenant_ai_usage_daily.unknown_usage_turns IS '用量不完整的回合数';
COMMENT ON COLUMN tenant_ai_usage_daily.reported_at IS 'AI 服务形成快照的时间';
COMMENT ON COLUMN tenant_ai_usage_daily.created_at IS '创建时间';
COMMENT ON COLUMN tenant_ai_usage_daily.updated_at IS '更新时间';
COMMENT ON COLUMN tenant_ai_usage_daily.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN tenant_ai_usage_daily.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN tenant_ai_usage_daily.deleted IS '是否逻辑删除';
CREATE UNIQUE INDEX tenant_ai_usage_daily_tenant ON tenant_ai_usage_daily (tenant_id,usage_date) WHERE NOT deleted;
