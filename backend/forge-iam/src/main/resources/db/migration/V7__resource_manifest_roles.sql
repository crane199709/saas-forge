-- Resource Manifest 与 Permission Code（工单 12）。清单按 service + version 存储且不可变；每个服务最近一次上报的新版本为当前版本。
CREATE TABLE resource_manifest (
    id uuid PRIMARY KEY,
    service varchar(64) NOT NULL,
    version varchar(64) NOT NULL,
    content jsonb NOT NULL,
    current boolean NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE resource_manifest IS 'Resource Manifest：各服务上报的资源清单，按服务与版本存储且不可变';
COMMENT ON COLUMN resource_manifest.id IS '清单 ID，UUIDv7';
COMMENT ON COLUMN resource_manifest.service IS '上报清单的服务';
COMMENT ON COLUMN resource_manifest.version IS '清单版本';
COMMENT ON COLUMN resource_manifest.content IS '清单内容 JSON';
COMMENT ON COLUMN resource_manifest.current IS '是否为该服务的当前版本';
COMMENT ON COLUMN resource_manifest.created_at IS '创建时间';
COMMENT ON COLUMN resource_manifest.updated_at IS '最后更新时间';
COMMENT ON COLUMN resource_manifest.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN resource_manifest.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN resource_manifest.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX resource_manifest_service_version_key ON resource_manifest (service, version) WHERE NOT deleted;
CREATE UNIQUE INDEX resource_manifest_current_key ON resource_manifest (service) WHERE current AND NOT deleted;

-- Permission Code 全平台唯一并归属申报它的服务；下线的编码标记 inactive，保留已有授权记录，不物理删除。
CREATE TABLE permission (
    id uuid PRIMARY KEY,
    code varchar(128) NOT NULL,
    service varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    group_name varchar(120),
    status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE permission IS 'Permission Code：全平台唯一并归属申报它的服务';
COMMENT ON COLUMN permission.id IS '权限 ID，UUIDv7';
COMMENT ON COLUMN permission.code IS '权限码，未删除的编码唯一';
COMMENT ON COLUMN permission.service IS '申报该权限码的服务';
COMMENT ON COLUMN permission.name IS '权限名称';
COMMENT ON COLUMN permission.group_name IS '权限分组名称';
COMMENT ON COLUMN permission.status IS '状态：active 有效，inactive 已下线（保留已有授权但不生效）';
COMMENT ON COLUMN permission.created_at IS '创建时间';
COMMENT ON COLUMN permission.updated_at IS '最后更新时间';
COMMENT ON COLUMN permission.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN permission.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN permission.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX permission_code_key ON permission (code) WHERE NOT deleted;

-- 租户角色。与 membership 一样不启用 RLS：iam 在登录、签发 Token 时需要跨租户读取。
-- 预置角色（Owner、Tenant Admin）由数据库兜底：builtin 与保留编码绑定，自定义角色不能占用保留编码，
-- 预置角色既不能改为非预置，也不能被删除。
CREATE TABLE role (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    builtin boolean NOT NULL DEFAULT false,
    owner boolean NOT NULL GENERATED ALWAYS AS (code = 'owner') STORED,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT role_builtin_code CHECK (builtin = (code IN ('owner', 'tenant-admin'))),
    CONSTRAINT role_builtin_not_deleted CHECK (NOT (builtin AND deleted))
);
COMMENT ON TABLE role IS '租户角色：含预置角色（Owner、Tenant Admin）与自定义角色';
COMMENT ON COLUMN role.id IS '角色 ID，UUIDv7';
COMMENT ON COLUMN role.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN role.code IS '角色编码，租户内唯一，owner 与 tenant-admin 为保留编码';
COMMENT ON COLUMN role.name IS '角色名称';
COMMENT ON COLUMN role.builtin IS '是否为预置角色，预置角色不能删除';
COMMENT ON COLUMN role.owner IS '是否为 Owner 角色，由 code 生成，供 membership_role 复合外键引用';
COMMENT ON COLUMN role.created_at IS '创建时间';
COMMENT ON COLUMN role.updated_at IS '最后更新时间';
COMMENT ON COLUMN role.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN role.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN role.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX role_tenant_code_key ON role (tenant_id, code) WHERE NOT deleted;
-- 只供 membership_role 的复合外键引用；id 已是主键，此索引不额外约束唯一性，与部分唯一索引的规则不冲突。
CREATE UNIQUE INDEX role_id_owner_key ON role (id, owner);

-- 纯关联表，不带标准列。owner 经复合外键与角色本身一致，不能由调用方随意填写；
-- 部分唯一索引因此能保证每个租户的 Owner 角色只有一个持有者。
CREATE TABLE membership_role (
    membership_id uuid NOT NULL REFERENCES membership(id),
    role_id uuid NOT NULL,
    owner boolean NOT NULL DEFAULT false,
    PRIMARY KEY (membership_id, role_id),
    FOREIGN KEY (role_id, owner) REFERENCES role (id, owner)
);
COMMENT ON TABLE membership_role IS '成员与角色的关联，保证每个租户的 Owner 角色只有一个持有者';
COMMENT ON COLUMN membership_role.membership_id IS 'Membership ID';
COMMENT ON COLUMN membership_role.role_id IS '角色 ID';
COMMENT ON COLUMN membership_role.owner IS '是否为 Owner 角色，经复合外键与角色保持一致';
CREATE INDEX membership_role_role_id_idx ON membership_role (role_id);
CREATE UNIQUE INDEX membership_role_owner_key ON membership_role (role_id) WHERE owner;

-- 为已有租户补齐预置角色。PostgreSQL 17 没有 uuidv7()，这里按 RFC 9562 拼出 UUIDv7（ADR-0014）：
-- 前 48 位为毫秒时间戳，其余取随机 UUID，再把版本位改为 0111。新租户由 iam 在应用侧生成。
INSERT INTO role (id, tenant_id, code, name, builtin)
SELECT CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
           PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
           FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid),
       t.id, r.code, r.name, true
FROM tenant t
CROSS JOIN (VALUES ('owner', '所有者'), ('tenant-admin', '租户管理员')) AS r (code, name)
WHERE NOT t.deleted;
