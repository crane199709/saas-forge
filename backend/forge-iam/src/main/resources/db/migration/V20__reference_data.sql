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
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false,
    CHECK (custom = (code LIKE 'custom.%')),
    CHECK (NOT custom OR (name IS NOT NULL AND sort_order IS NOT NULL))
);
CREATE UNIQUE INDEX dictionary_item_tenant_code_key
    ON dictionary_item (tenant_id, service, dictionary, code) WHERE NOT deleted;

-- 租户对 Parameter 的覆盖值，JSON 写法同 Manifest 中的默认值；恢复默认即逻辑删除。Manifest 删除参数后行保留但不生效。
CREATE TABLE parameter_override (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    code varchar(64) NOT NULL,
    value jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX parameter_override_tenant_code_key ON parameter_override (tenant_id, service, code) WHERE NOT deleted;
