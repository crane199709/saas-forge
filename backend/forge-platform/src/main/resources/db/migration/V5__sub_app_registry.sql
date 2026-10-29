-- App Registry（工单 21）：Sub-App 编码、各环境 remoteEntry 地址、版本与所需最低共享依赖版本。平台数据，不按租户隔离。
CREATE TABLE sub_app (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    -- 仅用于展示与排查；切换版本靠修改入口地址，Shell 不按它决定加载哪个构建
    version varchar(64) NOT NULL,
    -- 每次变更取自 sub_app_revision，随 SubAppChanged 发布；全局递增，删除后以同一编码重新登记也比删除时更大
    revision bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX sub_app_code_key ON sub_app (code) WHERE NOT deleted;
CREATE SEQUENCE sub_app_revision;

-- 各环境的入口地址。纯关联表，不带标准列；随每次变更整体替换。
CREATE TABLE sub_app_entry (
    sub_app_id uuid NOT NULL REFERENCES sub_app(id),
    environment varchar(32) NOT NULL,
    remote_entry varchar(2048) NOT NULL,
    PRIMARY KEY (sub_app_id, environment)
);

-- 所需的最低共享依赖版本（x.y.z）。纯关联表，不带标准列；随每次变更整体替换。
CREATE TABLE sub_app_requirement (
    sub_app_id uuid NOT NULL REFERENCES sub_app(id),
    dependency varchar(128) NOT NULL,
    min_version varchar(32) NOT NULL,
    PRIMARY KEY (sub_app_id, dependency)
);
