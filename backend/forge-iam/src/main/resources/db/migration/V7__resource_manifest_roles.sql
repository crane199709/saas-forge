-- Resource Manifest 与 Permission Code（工单 12）。清单按 service + version 存储且不可变；每个服务最近一次上报的新版本为当前版本。
CREATE TABLE resource_manifest (
    id uuid PRIMARY KEY,
    service varchar(64) NOT NULL,
    version varchar(64) NOT NULL,
    content jsonb NOT NULL,
    current boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
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
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
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
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT role_builtin_code CHECK (builtin = (code IN ('owner', 'tenant-admin'))),
    CONSTRAINT role_builtin_not_deleted CHECK (NOT (builtin AND deleted))
);
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
CREATE INDEX membership_role_role_id_idx ON membership_role (role_id);
CREATE UNIQUE INDEX membership_role_owner_key ON membership_role (role_id) WHERE owner;

-- 为已有租户补齐预置角色。PostgreSQL 17 没有 uuidv7()，这里按 RFC 9562 拼出 UUIDv7（ADR-0014）：
-- 前 48 位为毫秒时间戳，其余取随机 UUID，再把版本位改为 0111。新租户由 iam 在应用侧生成。
INSERT INTO role (id, tenant_id, code, name, builtin)
SELECT encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
           PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
           FROM 1 FOR 6), 52, 1), 53, 1), 'hex')::uuid,
       t.id, r.code, r.name, true
FROM tenant t
CROSS JOIN (VALUES ('owner', '所有者'), ('tenant-admin', '租户管理员')) AS r (code, name)
WHERE NOT t.deleted;
