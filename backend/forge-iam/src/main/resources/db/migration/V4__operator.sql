-- Platform Realm 的账号独立建表，不与 Identity 共用（ADR-0003）；Operator 不属于任何租户，因此没有 tenant_id 与 RLS。
CREATE TABLE operator (
    id uuid PRIMARY KEY,
    account varchar(120) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active'
);
COMMENT ON TABLE operator IS '平台操作员：Platform Realm 的账号，不属于任何租户（ADR-0003）';
COMMENT ON COLUMN operator.id IS 'Operator ID，UUIDv7';
COMMENT ON COLUMN operator.account IS '登录账号，未删除的账号唯一';
COMMENT ON COLUMN operator.display_name IS '显示名称';
COMMENT ON COLUMN operator.password_hash IS '密码哈希';
COMMENT ON COLUMN operator.status IS '状态：active 有效，disabled 已停用';
