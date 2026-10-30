#set( $dollar = '$' )
#set( $hash = '#' )
-- 订单分类（工单 14）：演示 Provisioning 时为新租户预置本服务的默认数据，与 orders 一样按租户隔离。
CREATE TABLE order_categories (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
-- 同时兜底重复初始化：并发或重试插入同一预置分类时违反唯一索引，而不是产生重复数据。
CREATE UNIQUE INDEX order_categories_tenant_code_key ON order_categories (tenant_id, code) WHERE NOT deleted;

ALTER TABLE order_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_categories FORCE ROW LEVEL SECURITY;

CREATE POLICY order_categories_tenant_isolation ON order_categories
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
