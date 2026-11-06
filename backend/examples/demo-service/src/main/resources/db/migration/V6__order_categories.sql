-- 订单分类（工单 14）：演示 Provisioning 时为新租户预置本服务的默认数据，与 orders 一样按租户隔离。
CREATE TABLE order_categories (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE order_categories IS '示例订单分类：Provisioning 时为新租户预置';
COMMENT ON COLUMN order_categories.id IS '分类 ID，UUIDv7';
COMMENT ON COLUMN order_categories.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN order_categories.code IS '分类编码，租户内唯一';
COMMENT ON COLUMN order_categories.name IS '分类名称';
COMMENT ON COLUMN order_categories.created_at IS '创建时间';
COMMENT ON COLUMN order_categories.updated_at IS '最后更新时间';
COMMENT ON COLUMN order_categories.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_categories.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_categories.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
-- 同时兜底重复初始化：并发或重试插入同一预置分类时违反唯一索引，而不是产生重复数据。
CREATE UNIQUE INDEX order_categories_tenant_code_key ON order_categories (tenant_id, code) WHERE NOT deleted;

ALTER TABLE order_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_categories FORCE ROW LEVEL SECURITY;

CREATE POLICY order_categories_tenant_isolation ON order_categories
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
