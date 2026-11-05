-- Business Number（字典、参数与编号规格第 5 节）：forge-sdk-numbering 需要的计数表，与 SDK 中
-- io/saasforge/sdk/numbering/schema.sql 一致；按租户启用 RLS。
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

-- 订单编号 SO-yyyyMMdd-NNNN，取号与订单同一事务。存量订单在引入编号前创建，没有编号，因此允许为空；
-- 空值互不冲突，部分唯一索引只约束已有编号的订单。
ALTER TABLE orders ADD COLUMN number varchar(64);
CREATE UNIQUE INDEX orders_number_key ON orders (tenant_id, number) WHERE NOT deleted;
