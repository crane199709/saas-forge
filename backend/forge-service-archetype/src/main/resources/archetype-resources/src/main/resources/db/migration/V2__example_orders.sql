#set( $dollar = '$' )
#set( $hash = '#' )
-- 示例业务表（订单、订单通知、订单分类）：替换为本服务领域时，与订单示例代码一起整体删除本文件，再从 V2 起编写自己的迁移。
-- 开发者不写租户代码，tenant_id 由 SDK 注入，RLS 作为第二道防线。
CREATE TABLE orders (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    amount numeric(12, 2) NOT NULL CHECK (amount >= 0),
    owner_id uuid,
    department_path ltree,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE orders IS '示例订单';
COMMENT ON COLUMN orders.id IS '订单 ID，UUIDv7';
COMMENT ON COLUMN orders.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN orders.title IS '订单标题';
COMMENT ON COLUMN orders.amount IS '订单金额';
COMMENT ON COLUMN orders.owner_id IS '数据归属的 Membership ID，供「本人」Data Scope 过滤';
COMMENT ON COLUMN orders.department_path IS '数据归属的部门路径，供「本部门」「本部门及下级」Data Scope 过滤';
COMMENT ON COLUMN orders.created_at IS '创建时间';
COMMENT ON COLUMN orders.updated_at IS '最后更新时间';
COMMENT ON COLUMN orders.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN orders.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN orders.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE INDEX orders_tenant_id_idx ON orders (tenant_id);
CREATE INDEX orders_owner_id_idx ON orders (owner_id);
-- GiST 索引支持 ltree 的 <@ 子树查询（「本部门及下级」）
CREATE INDEX orders_department_path_idx ON orders USING gist (department_path);

-- FORCE 让表所有者（迁移账号）也受 RLS 约束，否则以迁移账号直连即可绕过隔离。
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;

-- 会话变量由 SDK 在每次取连接时设置；未设置或置空时为 NULL，任何行都不可见、不可写。
CREATE POLICY orders_tenant_isolation ON orders
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));

-- 示例消费方的业务表：消费「订单已创建」后写入，与 orders 一样按租户隔离。
CREATE TABLE order_notifications (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    title varchar(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE order_notifications IS '示例订单通知：消费「订单已创建」事件后写入';
COMMENT ON COLUMN order_notifications.id IS '通知 ID，UUIDv7';
COMMENT ON COLUMN order_notifications.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN order_notifications.order_id IS '关联的订单 ID';
COMMENT ON COLUMN order_notifications.title IS '通知标题';
COMMENT ON COLUMN order_notifications.created_at IS '创建时间';
COMMENT ON COLUMN order_notifications.updated_at IS '最后更新时间';
COMMENT ON COLUMN order_notifications.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_notifications.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_notifications.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE INDEX order_notifications_tenant_id_idx ON order_notifications (tenant_id);

ALTER TABLE order_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY order_notifications_tenant_isolation ON order_notifications
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));

-- 订单分类：演示 Provisioning 时为新租户预置本服务的默认数据，与 orders 一样按租户隔离。
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
