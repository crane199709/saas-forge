-- 租户 Plan 的副本（工单 20）：platform 广播 TenantPlanChanged，iam 据此限定可授予与生效的 Permission Code，并校验成员数配额。
-- 没有记录的租户（套餐上线前创建的）不受套餐限制。不建到 tenant 的外键：Plan 变更可能先于 TenantCreated 送达。
CREATE TABLE tenant_plan (
    tenant_id uuid PRIMARY KEY,
    plan_id uuid NOT NULL,
    -- 已应用的 Plan 版本：TenantPlanChanged 可能乱序或重复送达，只应用更新的版本
    plan_version bigint NOT NULL,
    -- 成员数上限，NULL 表示不限
    member_quota integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);

-- Feature Pack 中的权限码。纯关联表，不带标准列；随每次变更整体替换。
-- 不引用 permission 表：Plan 可以包含已下线的编码，业务服务重新上报后随之恢复。
CREATE TABLE tenant_plan_permission (
    tenant_id uuid NOT NULL REFERENCES tenant_plan(tenant_id),
    code varchar(128) NOT NULL,
    PRIMARY KEY (tenant_id, code)
);
