-- Plan（工单 20）：Feature Pack（可用的 Permission Code 与 Sub-App）与 Quota（成员数、存储容量）。平台数据，不按租户隔离。
CREATE TABLE plan (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    -- Feature Pack：权限码取自 iam 的 Resource Manifest 目录；Sub-App 编码在 App Registry（工单 21）上线前只作为编码维护
    permissions text[] NOT NULL DEFAULT '{}',
    sub_apps text[] NOT NULL DEFAULT '{}',
    -- Quota，NULL 表示不限
    member_quota integer CHECK (member_quota > 0),
    storage_quota_mb bigint CHECK (storage_quota_mb > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX plan_code_key ON plan (code) WHERE NOT deleted;

ALTER TABLE tenant
    -- 套餐上线前创建的租户没有 Plan，各服务对其不做套餐限制
    ADD COLUMN plan_id uuid REFERENCES plan(id),
    -- 每次 Plan 变更（更换 Plan，或所用 Plan 的内容被修改）加 1，随 TenantPlanChanged 发布，订阅方据此丢弃乱序送达的旧变更
    ADD COLUMN plan_version bigint NOT NULL DEFAULT 0;
CREATE INDEX tenant_plan_idx ON tenant (plan_id) WHERE NOT deleted;
