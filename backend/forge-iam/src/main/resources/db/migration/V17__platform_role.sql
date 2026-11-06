-- 平台侧权限（工单 22）。平台与租户共用同一套权限引擎（ADR-0003），数据完全分离：Platform Realm 服务申报的权限码只能授予平台角色，
-- 平台角色、Operator 的角色分配、平台角色的授权与 Data Policy 各自建表，与租户侧的 role、membership_role 等表没有关联。

-- 权限码所属的 Realm，取自申报它的清单。已有权限码都来自 Tenant Realm 服务。
ALTER TABLE permission ADD COLUMN realm varchar(16) NOT NULL DEFAULT 'tenant' CHECK (realm IN ('tenant', 'platform'));
COMMENT ON COLUMN permission.realm IS '权限码所属 Realm：tenant 或 platform，取自申报它的清单';

-- version 与 Membership 的 version 作用相同：停用、角色、授权、Data Policy 或负责区域变化时递增，旧 Token 立即失效（ADR-0005）。
-- region 是 Operator 负责的区域，作为平台 Data Policy 的主体属性 subject.attributes.region。
ALTER TABLE operator
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN region varchar(64),
    ADD CONSTRAINT operator_status_check CHECK (status IN ('active', 'disabled'));
COMMENT ON COLUMN operator.version IS '版本号，停用、角色、授权、Data Policy 或负责区域变化时递增，使旧 Token 立即失效（ADR-0005）';
COMMENT ON COLUMN operator.region IS '负责的区域，作为平台 Data Policy 的主体属性 subject.attributes.region';

-- 平台角色。超级管理员（super-admin）是唯一的预置角色：拥有全部有效的平台权限码，在全部平台数据资源上为「全部」，
-- 不落授权记录，不能修改或删除；预置与保留编码由 CHECK 绑定，与租户角色的做法一致。
CREATE TABLE platform_role (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    builtin boolean NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT platform_role_builtin_code CHECK (builtin = (code = 'super-admin')),
    CONSTRAINT platform_role_builtin_not_deleted CHECK (NOT (builtin AND deleted))
);
COMMENT ON TABLE platform_role IS '平台角色：超级管理员（super-admin）是唯一的预置角色';
COMMENT ON COLUMN platform_role.id IS '平台角色 ID，UUIDv7';
COMMENT ON COLUMN platform_role.code IS '角色编码，未删除的编码唯一，super-admin 为保留编码';
COMMENT ON COLUMN platform_role.name IS '角色名称';
COMMENT ON COLUMN platform_role.builtin IS '是否为预置角色，预置角色不能修改或删除';
COMMENT ON COLUMN platform_role.created_at IS '创建时间';
COMMENT ON COLUMN platform_role.updated_at IS '最后更新时间';
COMMENT ON COLUMN platform_role.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN platform_role.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN platform_role.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX platform_role_code_key ON platform_role (code) WHERE NOT deleted;

-- 以下均为纯关联表，不带标准列。
CREATE TABLE operator_role (
    operator_id uuid NOT NULL REFERENCES operator(id),
    role_id uuid NOT NULL REFERENCES platform_role(id),
    PRIMARY KEY (operator_id, role_id)
);
COMMENT ON TABLE operator_role IS 'Operator 与平台角色的关联';
COMMENT ON COLUMN operator_role.operator_id IS 'Operator ID';
COMMENT ON COLUMN operator_role.role_id IS '平台角色 ID';
CREATE INDEX operator_role_role_id_idx ON operator_role (role_id);

-- 平台权限码下线后保留授权记录但不生效，再次上报恢复后随之恢复，同 role_permission。
CREATE TABLE platform_role_permission (
    role_id uuid NOT NULL REFERENCES platform_role(id),
    permission_id uuid NOT NULL REFERENCES permission(id),
    PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE platform_role_permission IS '平台角色的 Permission Code 授权';
COMMENT ON COLUMN platform_role_permission.role_id IS '平台角色 ID';
COMMENT ON COLUMN platform_role_permission.permission_id IS '权限 ID';
CREATE INDEX platform_role_permission_permission_id_idx ON platform_role_permission (permission_id);

-- 规则同 role_data_policy：每个角色在每个平台数据资源上至多一条，预设与自定义条件二选一。
CREATE TABLE platform_role_data_policy (
    role_id uuid NOT NULL REFERENCES platform_role(id),
    resource varchar(64) NOT NULL,
    scope varchar(32) NOT NULL,
    condition jsonb,
    PRIMARY KEY (role_id, resource),
    CONSTRAINT platform_role_data_policy_scope_check CHECK (
        (scope IN ('self', 'department', 'department_and_below', 'all') AND condition IS NULL)
        OR (scope = 'custom' AND jsonb_typeof(condition) = 'object'))
);
COMMENT ON TABLE platform_role_data_policy IS '平台角色的 Data Policy：每个角色在每个平台数据资源上至多一条';
COMMENT ON COLUMN platform_role_data_policy.role_id IS '平台角色 ID';
COMMENT ON COLUMN platform_role_data_policy.resource IS '平台数据资源编码';
COMMENT ON COLUMN platform_role_data_policy.scope IS 'Data Scope：self、department、department_and_below、all 或 custom';
COMMENT ON COLUMN platform_role_data_policy.condition IS '自定义条件的 JSON 表达式树，仅 scope 为 custom 时有值';

-- UUIDv7 的拼法见 V7。
INSERT INTO platform_role (id, code, name, builtin)
VALUES (CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
            PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
            FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid),
        'super-admin', '超级管理员', true);

-- 此前任一已认证 Operator 都能管理全部平台功能；已有 Operator 升级后成为超级管理员，保持原有能力，也保证至少有人能分配角色。
INSERT INTO operator_role (operator_id, role_id)
SELECT o.id, r.id FROM operator o CROSS JOIN platform_role r
WHERE r.code = 'super-admin' AND NOT o.deleted;
