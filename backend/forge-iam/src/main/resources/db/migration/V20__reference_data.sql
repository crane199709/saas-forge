-- 租户对参考数据的修改（参考数据工单 02）。默认值来自各服务的 Resource Manifest，租户只在修改或自定义时才有行。
-- 与其他 iam 表一样不启用 RLS（ADR-0001 的 iam 例外），租户条件由 mapper XML 显式书写。

-- 一行是租户对某个字典项的设置：内置项只在租户改过时才有行，name、sort_order 为 NULL 表示沿用声明；自定义项编码带 custom. 前缀，
-- 只能停用不能删除，编码不会被重新使用。
CREATE TABLE dictionary_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    dictionary varchar(64) NOT NULL,
    code varchar(64) NOT NULL,
    custom boolean NOT NULL,
    name varchar(120),
    sort_order integer,
    enabled boolean NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false,
    CHECK (custom = (code LIKE 'custom.%')),
    CHECK (NOT custom OR (name IS NOT NULL AND sort_order IS NOT NULL))
);
COMMENT ON TABLE dictionary_item IS '租户对字典项的设置：内置项改过才有行，自定义项编码带 custom. 前缀';
COMMENT ON COLUMN dictionary_item.id IS '主键，UUIDv7';
COMMENT ON COLUMN dictionary_item.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN dictionary_item.service IS '声明字典的服务';
COMMENT ON COLUMN dictionary_item.dictionary IS '字典编码';
COMMENT ON COLUMN dictionary_item.code IS '字典项编码';
COMMENT ON COLUMN dictionary_item.custom IS '是否为自定义项，与编码前缀 custom. 一致';
COMMENT ON COLUMN dictionary_item.name IS '字典项名称，内置项为 NULL 时沿用声明';
COMMENT ON COLUMN dictionary_item.sort_order IS '排序值，内置项为 NULL 时沿用声明';
COMMENT ON COLUMN dictionary_item.enabled IS '是否启用，自定义项只能停用不能删除';
COMMENT ON COLUMN dictionary_item.created_at IS '创建时间';
COMMENT ON COLUMN dictionary_item.updated_at IS '最后更新时间';
COMMENT ON COLUMN dictionary_item.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN dictionary_item.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN dictionary_item.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX dictionary_item_tenant_code_key
    ON dictionary_item (tenant_id, service, dictionary, code) WHERE NOT deleted;

-- 租户对 Parameter 的覆盖值，JSON 写法同 Manifest 中的默认值；恢复默认即逻辑删除。Manifest 删除参数后行保留但不生效。
CREATE TABLE parameter_override (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    code varchar(64) NOT NULL,
    value jsonb NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE parameter_override IS '租户对 Parameter 的覆盖值：恢复默认即逻辑删除';
COMMENT ON COLUMN parameter_override.id IS '主键，UUIDv7';
COMMENT ON COLUMN parameter_override.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN parameter_override.service IS '声明参数的服务';
COMMENT ON COLUMN parameter_override.code IS '参数编码';
COMMENT ON COLUMN parameter_override.value IS '覆盖值 JSON，写法同 Manifest 中的默认值';
COMMENT ON COLUMN parameter_override.created_at IS '创建时间';
COMMENT ON COLUMN parameter_override.updated_at IS '最后更新时间';
COMMENT ON COLUMN parameter_override.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN parameter_override.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN parameter_override.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX parameter_override_tenant_code_key ON parameter_override (tenant_id, service, code) WHERE NOT deleted;
