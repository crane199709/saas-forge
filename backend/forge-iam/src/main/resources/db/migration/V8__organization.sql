-- 组织模型（工单 13）：Department 树、Position、Extended Attribute 定义，以及 Membership 的部门、岗位与扩展属性值。
-- 与 membership、role 一样不启用 RLS：iam 没有租户会话变量，隔离由 mapper 中取自 Token 的 tenant_id 条件保证；
-- 跨表引用一律经 (tenant_id, id) 复合外键，数据库层面就不能把成员分配到其他租户的部门或岗位。
-- ltree 由数据库初始化脚本装在 extensions schema，服务账号的 search_path 已包含该 schema。

-- 以下 (tenant_id, id) 唯一索引只供复合外键引用；id 已是主键，不额外约束唯一性，与部分唯一索引的规则不冲突。
CREATE UNIQUE INDEX membership_tenant_id_key ON membership (tenant_id, id);

CREATE TABLE department (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    parent_id uuid,
    name varchar(120) NOT NULL,
    -- 自根到本部门各级 id（去掉连字符）连成的路径：改名不变，移动时整棵子树一起改写
    path ltree NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE department IS '部门：租户内的组织树节点';
COMMENT ON COLUMN department.id IS '部门 ID，UUIDv7';
COMMENT ON COLUMN department.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN department.parent_id IS '上级部门 ID，根部门为 NULL';
COMMENT ON COLUMN department.name IS '部门名称';
COMMENT ON COLUMN department.path IS '自根到本部门各级 ID（去掉连字符）连成的 ltree 路径，移动时整棵子树一起改写';
COMMENT ON COLUMN department.created_at IS '创建时间';
COMMENT ON COLUMN department.updated_at IS '最后更新时间';
COMMENT ON COLUMN department.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN department.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN department.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX department_tenant_id_key ON department (tenant_id, id);
ALTER TABLE department ADD FOREIGN KEY (tenant_id, parent_id) REFERENCES department (tenant_id, id);
CREATE UNIQUE INDEX department_tenant_path_key ON department (tenant_id, path) WHERE NOT deleted;
CREATE INDEX department_path_idx ON department USING gist (path);

CREATE TABLE position (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    name varchar(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE position IS '岗位：租户内定义的岗位';
COMMENT ON COLUMN position.id IS '岗位 ID，UUIDv7';
COMMENT ON COLUMN position.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN position.name IS '岗位名称';
COMMENT ON COLUMN position.created_at IS '创建时间';
COMMENT ON COLUMN position.updated_at IS '最后更新时间';
COMMENT ON COLUMN position.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN position.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN position.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX position_tenant_id_key ON position (tenant_id, id);

-- 编码是扩展属性值在 membership.attributes 中的键，也是主体属性名 subject.attributes.<code> 的后缀，创建后不可修改。
CREATE TABLE extended_attribute (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code varchar(64) NOT NULL CHECK (code ~ '^[a-z][A-Za-z0-9]*$'),
    name varchar(120) NOT NULL,
    type varchar(20) NOT NULL CHECK (type IN ('string', 'number', 'boolean')),
    required boolean NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE extended_attribute IS '扩展属性定义：租户自定义的成员属性';
COMMENT ON COLUMN extended_attribute.id IS '扩展属性 ID，UUIDv7';
COMMENT ON COLUMN extended_attribute.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN extended_attribute.code IS '属性编码，是 membership.attributes 的键，创建后不可修改';
COMMENT ON COLUMN extended_attribute.name IS '属性名称';
COMMENT ON COLUMN extended_attribute.type IS '属性类型：string、number 或 boolean';
COMMENT ON COLUMN extended_attribute.required IS '是否必填';
COMMENT ON COLUMN extended_attribute.created_at IS '创建时间';
COMMENT ON COLUMN extended_attribute.updated_at IS '最后更新时间';
COMMENT ON COLUMN extended_attribute.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN extended_attribute.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN extended_attribute.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX extended_attribute_tenant_code_key ON extended_attribute (tenant_id, code) WHERE NOT deleted;

ALTER TABLE membership
    ADD COLUMN attributes jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(attributes) = 'object');
COMMENT ON COLUMN membership.attributes IS '扩展属性值 JSON 对象，键为 extended_attribute.code';

-- 纯关联表，不带标准列，重新分配时整体替换。每个 Membership 至多一个主部门；有兼任部门时必须有主部门由 iam 校验。
CREATE TABLE membership_department (
    membership_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    department_id uuid NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    PRIMARY KEY (membership_id, department_id),
    FOREIGN KEY (tenant_id, membership_id) REFERENCES membership (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id)
);
COMMENT ON TABLE membership_department IS '成员与部门的关联，重新分配时整体替换';
COMMENT ON COLUMN membership_department.membership_id IS 'Membership ID';
COMMENT ON COLUMN membership_department.tenant_id IS '所属租户 ID，经复合外键防止跨租户分配';
COMMENT ON COLUMN membership_department.department_id IS '部门 ID';
COMMENT ON COLUMN membership_department.is_primary IS '是否为主部门，每个成员至多一个';
CREATE UNIQUE INDEX membership_department_primary_key ON membership_department (membership_id) WHERE is_primary;
CREATE INDEX membership_department_department_id_idx ON membership_department (department_id);

CREATE TABLE membership_position (
    membership_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    position_id uuid NOT NULL,
    PRIMARY KEY (membership_id, position_id),
    FOREIGN KEY (tenant_id, membership_id) REFERENCES membership (tenant_id, id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES position (tenant_id, id)
);
COMMENT ON TABLE membership_position IS '成员与岗位的关联';
COMMENT ON COLUMN membership_position.membership_id IS 'Membership ID';
COMMENT ON COLUMN membership_position.tenant_id IS '所属租户 ID，经复合外键防止跨租户分配';
COMMENT ON COLUMN membership_position.position_id IS '岗位 ID';
CREATE INDEX membership_position_position_id_idx ON membership_position (position_id);
