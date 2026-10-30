#set( $dollar = '$' )
#set( $hash = '#' )
-- 示例业务表：开发者不写租户代码，tenant_id 由 SDK 注入，RLS 作为第二道防线（ADR-0001）。
CREATE TABLE orders (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    amount numeric(12, 2) NOT NULL CHECK (amount >= 0)
);
CREATE INDEX orders_tenant_id_idx ON orders (tenant_id);

-- FORCE 让表所有者（迁移账号）也受 RLS 约束，否则以迁移账号直连即可绕过隔离。
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;

-- 会话变量由 SDK 在每次取连接时设置；未设置或置空时为 NULL，任何行都不可见、不可写。
CREATE POLICY orders_tenant_isolation ON orders
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
