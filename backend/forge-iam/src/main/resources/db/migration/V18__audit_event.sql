-- 审计（工单 23）。各服务产出的 Audit Event 经 RocketMQ 送达 iam，写入按月分区的 audit_event。
-- 审计是只追加的事实记录，不带标准列、不做逻辑删除（编码规范「建表与标准列」）；按月分区便于将来整月归档或删除。
-- 不按租户启用 RLS：同一张表同时存放 Tenant Realm 与 Platform Realm 的事件，查询在 mapper 中显式限定 realm 与租户。

CREATE TABLE audit_event (
    id uuid NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    realm varchar(16) NOT NULL CHECK (realm IN ('tenant', 'platform')),
    -- Platform Realm 事件与尚未确定租户的登录事件（登录失败、退出）为 NULL
    tenant_id uuid,
    -- Tenant Realm 为 Identity id，Platform Realm 为 Operator id；不建外键，操作人可能已删除或无法确定
    actor_id uuid,
    category varchar(16) NOT NULL CHECK (category IN ('login', 'permission', 'operation')),
    action varchar(128) NOT NULL,
    resource_type varchar(64),
    resource_id varchar(255),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('success', 'failure')),
    error_code varchar(128),
    detail jsonb NOT NULL DEFAULT '{}',
    service varchar(128) NOT NULL,
    trace_id varchar(64),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 分区表的主键必须包含分区键；事件 ID 与发生时间都由产出方确定，重投的同一事件得到同一主键
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
COMMENT ON TABLE audit_event IS '审计事件：各服务产出的只追加事实记录，按月分区';
COMMENT ON COLUMN audit_event.id IS '事件 ID，由产出方生成';
COMMENT ON COLUMN audit_event.occurred_at IS '事件发生时间，也是分区键';
COMMENT ON COLUMN audit_event.realm IS '事件所属 Realm：tenant 或 platform';
COMMENT ON COLUMN audit_event.tenant_id IS '所属租户 ID，Platform Realm 事件与尚未确定租户的登录事件为 NULL';
COMMENT ON COLUMN audit_event.actor_id IS '操作人 ID：Tenant Realm 为 Identity ID，Platform Realm 为 Operator ID';
COMMENT ON COLUMN audit_event.category IS '事件类别：login、permission 或 operation';
COMMENT ON COLUMN audit_event.action IS '动作编码';
COMMENT ON COLUMN audit_event.resource_type IS '被操作资源的类型';
COMMENT ON COLUMN audit_event.resource_id IS '被操作资源的 ID';
COMMENT ON COLUMN audit_event.outcome IS '结果：success 或 failure';
COMMENT ON COLUMN audit_event.error_code IS '失败时的错误码';
COMMENT ON COLUMN audit_event.detail IS '事件详情 JSON';
COMMENT ON COLUMN audit_event.service IS '产出事件的服务';
COMMENT ON COLUMN audit_event.trace_id IS '链路追踪 ID';
COMMENT ON COLUMN audit_event.received_at IS 'iam 收到事件的时间';

-- 两类查询：Tenant Admin 按租户、超级管理员按 Realm，都按时间倒序分页
CREATE INDEX audit_event_tenant_idx ON audit_event (tenant_id, occurred_at DESC);
CREATE INDEX audit_event_realm_idx ON audit_event (realm, occurred_at DESC);

-- 确保某月的分区存在。分区按 UTC 自然月划分，名为 audit_event_YYYY_MM。
-- 运行账号不是表所有者，无权建分区，因此以 SECURITY DEFINER 用迁移账号的身份执行；search_path 固定为迁移时的值，
-- 调用方无法借 search_path 让函数解析到其他对象。新分区由迁移账号创建，按 schema 的默认权限自动授予运行账号读写。
-- EXECUTE 保持默认授予 PUBLIC：只有获准使用本 schema 的账号（迁移账号与运行账号）能调用到它。
CREATE FUNCTION audit_event_ensure_partition(month_start date) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path FROM CURRENT
AS $$
DECLARE
    first_day date := CAST(date_trunc('month', month_start) AS date);
    partition_name text := 'audit_event_' || to_char(first_day, 'YYYY_MM');
BEGIN
    IF to_regclass(partition_name) IS NOT NULL THEN
        RETURN;
    END IF;
    -- 多个实例同时为同一个月建分区时串行执行，后到者看到已存在后返回
    PERFORM pg_advisory_xact_lock(hashtext(partition_name));
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
        partition_name,
        CAST(first_day AS timestamp) AT TIME ZONE 'UTC',
        CAST(first_day + INTERVAL '1' MONTH AS timestamp) AT TIME ZONE 'UTC');
END;
$$;
