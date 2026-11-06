-- App Registry（工单 21）：Sub-App 编码、各环境 remoteEntry 地址、版本与所需最低共享依赖版本。平台数据，不按租户隔离。
CREATE TABLE sub_app (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    -- 仅用于展示与排查；切换版本靠修改入口地址，Shell 不按它决定加载哪个构建
    version varchar(64) NOT NULL,
    -- 每次变更取自 sub_app_revision，随 SubAppChanged 发布；全局递增，删除后以同一编码重新登记也比删除时更大
    revision bigint NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE sub_app IS 'App Registry：登记 Sub-App 及其版本';
COMMENT ON COLUMN sub_app.id IS 'Sub-App ID，UUIDv7';
COMMENT ON COLUMN sub_app.code IS 'Sub-App 编码，未删除的编码唯一';
COMMENT ON COLUMN sub_app.name IS 'Sub-App 名称';
COMMENT ON COLUMN sub_app.version IS '版本，仅用于展示与排查';
COMMENT ON COLUMN sub_app.revision IS '全局递增的变更序号，随 SubAppChanged 发布';
COMMENT ON COLUMN sub_app.created_at IS '创建时间';
COMMENT ON COLUMN sub_app.updated_at IS '最后更新时间';
COMMENT ON COLUMN sub_app.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN sub_app.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN sub_app.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX sub_app_code_key ON sub_app (code) WHERE NOT deleted;
CREATE SEQUENCE sub_app_revision;

-- 各环境的入口地址。纯关联表，不带标准列；随每次变更整体替换。
CREATE TABLE sub_app_entry (
    sub_app_id uuid NOT NULL REFERENCES sub_app(id),
    environment varchar(32) NOT NULL,
    remote_entry varchar(2048) NOT NULL,
    PRIMARY KEY (sub_app_id, environment)
);
COMMENT ON TABLE sub_app_entry IS 'Sub-App 各环境的入口地址，随每次变更整体替换';
COMMENT ON COLUMN sub_app_entry.sub_app_id IS 'Sub-App ID';
COMMENT ON COLUMN sub_app_entry.environment IS '环境';
COMMENT ON COLUMN sub_app_entry.remote_entry IS 'remoteEntry 入口地址';

-- 所需的最低共享依赖版本（x.y.z）。纯关联表，不带标准列；随每次变更整体替换。
CREATE TABLE sub_app_requirement (
    sub_app_id uuid NOT NULL REFERENCES sub_app(id),
    dependency varchar(128) NOT NULL,
    min_version varchar(32) NOT NULL,
    PRIMARY KEY (sub_app_id, dependency)
);
COMMENT ON TABLE sub_app_requirement IS 'Sub-App 所需的最低共享依赖版本，随每次变更整体替换';
COMMENT ON COLUMN sub_app_requirement.sub_app_id IS 'Sub-App ID';
COMMENT ON COLUMN sub_app_requirement.dependency IS '共享依赖名称';
COMMENT ON COLUMN sub_app_requirement.min_version IS '所需最低版本（x.y.z）';
