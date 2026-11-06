CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active'
);
COMMENT ON TABLE tenant IS '租户副本：iam 据 platform 广播的事件维护，登录与签发 Token 时读取';
COMMENT ON COLUMN tenant.id IS '租户 ID，与 platform.tenant 相同';
COMMENT ON COLUMN tenant.name IS '租户名称';
COMMENT ON COLUMN tenant.status IS '租户状态，取值同 platform.tenant.status';
CREATE TABLE identity (
    id uuid PRIMARY KEY,
    account varchar(120) NOT NULL UNIQUE CHECK (account NOT LIKE '%@%'),
    email varchar(254) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active'
);
COMMENT ON TABLE identity IS '全局身份：一个人的登录凭证，可通过 Membership 加入多个租户';
COMMENT ON COLUMN identity.id IS 'Identity ID，UUIDv7';
COMMENT ON COLUMN identity.account IS '登录账号，不含 @，待激活时可为 NULL';
COMMENT ON COLUMN identity.email IS '邮箱，大小写不敏感唯一，与手机号至少有其一';
COMMENT ON COLUMN identity.display_name IS '显示名称';
COMMENT ON COLUMN identity.password_hash IS '密码哈希，待激活时可为 NULL';
COMMENT ON COLUMN identity.status IS '身份状态，如 pending 待激活、active 可登录';
CREATE TABLE membership (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES identity(id),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    status varchar(20) NOT NULL DEFAULT 'active',
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (identity_id, tenant_id)
);
COMMENT ON TABLE membership IS '成员关系：Identity 与租户的归属关系，同一对未删除的组合唯一';
COMMENT ON COLUMN membership.id IS 'Membership ID，UUIDv7';
COMMENT ON COLUMN membership.identity_id IS '所属 Identity ID';
COMMENT ON COLUMN membership.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN membership.status IS '成员状态：invited 已邀请，active 有效，suspended 已停用，left 已离开';
COMMENT ON COLUMN membership.version IS '成员版本号，停用、角色或权限等变化时递增，使旧 Token 立即失效（ADR-0005）';
