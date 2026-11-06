-- App Registry 的副本（工单 21）：platform 广播 SubAppChanged，iam 据此向 Shell App 下发可加载的 Sub-App 及其入口地址。
-- 按编码保存：删除只标记 deleted 并保留 revision，乱序送达的旧变更不会使其复活；重新登记的 revision 更大，随之恢复。
CREATE TABLE sub_app (
    code varchar(64) PRIMARY KEY,
    name varchar(120) NOT NULL,
    version varchar(64) NOT NULL,
    -- 已应用的变更序号：SubAppChanged 可能乱序或重复送达，只应用更新的序号
    revision bigint NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE sub_app IS 'App Registry 副本：向 Shell App 下发可加载的 Sub-App';
COMMENT ON COLUMN sub_app.code IS 'Sub-App 编码';
COMMENT ON COLUMN sub_app.name IS 'Sub-App 名称';
COMMENT ON COLUMN sub_app.version IS '版本，仅用于展示与排查';
COMMENT ON COLUMN sub_app.revision IS '已应用的变更序号，用于丢弃乱序送达的旧变更';
COMMENT ON COLUMN sub_app.created_at IS '创建时间';
COMMENT ON COLUMN sub_app.updated_at IS '最后更新时间';
COMMENT ON COLUMN sub_app.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN sub_app.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN sub_app.deleted IS '逻辑删除标记，true 表示对常规查询不可见';

-- 各环境的入口地址。纯关联表，不带标准列；随每次变更整体替换。
CREATE TABLE sub_app_entry (
    code varchar(64) NOT NULL REFERENCES sub_app(code),
    environment varchar(32) NOT NULL,
    remote_entry varchar(2048) NOT NULL,
    PRIMARY KEY (code, environment)
);
COMMENT ON TABLE sub_app_entry IS 'Sub-App 各环境的入口地址，随每次变更整体替换';
COMMENT ON COLUMN sub_app_entry.code IS 'Sub-App 编码';
COMMENT ON COLUMN sub_app_entry.environment IS '环境';
COMMENT ON COLUMN sub_app_entry.remote_entry IS 'remoteEntry 入口地址';

-- 所需的最低共享依赖版本。纯关联表，不带标准列；随每次变更整体替换。
CREATE TABLE sub_app_requirement (
    code varchar(64) NOT NULL REFERENCES sub_app(code),
    dependency varchar(128) NOT NULL,
    min_version varchar(32) NOT NULL,
    PRIMARY KEY (code, dependency)
);
COMMENT ON TABLE sub_app_requirement IS 'Sub-App 所需的最低共享依赖版本，随每次变更整体替换';
COMMENT ON COLUMN sub_app_requirement.code IS 'Sub-App 编码';
COMMENT ON COLUMN sub_app_requirement.dependency IS '共享依赖名称';
COMMENT ON COLUMN sub_app_requirement.min_version IS '所需最低版本（x.y.z）';

-- 租户 Plan Feature Pack 中的 Sub-App 编码，随 TenantPlanChanged 整体替换。纯关联表，不带标准列。
-- 不引用 sub_app：Plan 可以包含尚未登记或已删除的 Sub-App，登记后随之生效。
CREATE TABLE tenant_plan_sub_app (
    tenant_id uuid NOT NULL REFERENCES tenant_plan(tenant_id),
    code varchar(64) NOT NULL,
    PRIMARY KEY (tenant_id, code)
);
COMMENT ON TABLE tenant_plan_sub_app IS '租户 Plan Feature Pack 中的 Sub-App 编码，随每次变更整体替换';
COMMENT ON COLUMN tenant_plan_sub_app.tenant_id IS '租户 ID';
COMMENT ON COLUMN tenant_plan_sub_app.code IS 'Sub-App 编码，可包含尚未登记或已删除的 Sub-App';
