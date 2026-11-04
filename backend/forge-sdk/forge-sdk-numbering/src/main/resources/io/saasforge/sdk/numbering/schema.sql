-- forge-sdk-numbering 需要的表。服务把本文件内容复制到自己的 Flyway 迁移中执行，SDK 不自带迁移，
-- 以免与服务的迁移版本号交错；表结构变更时 SDK 会在发布说明中给出升级语句。
-- 每个「租户 + 规则 + 计数周期」一行，取号时加锁递增；带标准列，租户删除时由 SDK 逻辑删除。

CREATE TABLE forge_business_number (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    rule varchar(64) NOT NULL,
    -- 日期段的取值（yyyy / yyyyMM / yyyyMMdd），无日期段时为空串
    period varchar(8) NOT NULL,
    value bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
-- 同时是并发首次取号的冲突目标：INSERT ... ON CONFLICT 依赖它保证一个周期只有一行
CREATE UNIQUE INDEX forge_business_number_key ON forge_business_number (tenant_id, rule, period) WHERE NOT deleted;

ALTER TABLE forge_business_number ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_business_number FORCE ROW LEVEL SECURITY;

CREATE POLICY forge_business_number_tenant_isolation ON forge_business_number
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
