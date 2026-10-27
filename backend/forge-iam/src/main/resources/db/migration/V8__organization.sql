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
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX department_tenant_id_key ON department (tenant_id, id);
ALTER TABLE department ADD FOREIGN KEY (tenant_id, parent_id) REFERENCES department (tenant_id, id);
CREATE UNIQUE INDEX department_tenant_path_key ON department (tenant_id, path) WHERE NOT deleted;
CREATE INDEX department_path_idx ON department USING gist (path);

CREATE TABLE position (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX position_tenant_id_key ON position (tenant_id, id);

-- 编码是扩展属性值在 membership.attributes 中的键，也是主体属性名 subject.attributes.<code> 的后缀，创建后不可修改。
CREATE TABLE extended_attribute (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code varchar(64) NOT NULL CHECK (code ~ '^[a-z][A-Za-z0-9]*$'),
    name varchar(120) NOT NULL,
    type varchar(20) NOT NULL CHECK (type IN ('string', 'number', 'boolean')),
    required boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX extended_attribute_tenant_code_key ON extended_attribute (tenant_id, code) WHERE NOT deleted;

ALTER TABLE membership
    ADD COLUMN attributes jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(attributes) = 'object');

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
CREATE INDEX membership_position_position_id_idx ON membership_position (position_id);
