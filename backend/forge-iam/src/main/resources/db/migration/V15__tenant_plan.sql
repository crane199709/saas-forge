-- 租户 Plan 的副本（工单 20）：platform 广播 TenantPlanChanged，iam 据此限定可授予与生效的 Permission Code，并校验成员数配额。
-- 没有记录的租户（套餐上线前创建的）不受套餐限制。不建到 tenant 的外键：Plan 变更可能先于 TenantCreated 送达。
CREATE TABLE tenant_plan (
    tenant_id uuid PRIMARY KEY,
    plan_id uuid NOT NULL,
    -- 已应用的 Plan 版本：TenantPlanChanged 可能乱序或重复送达，只应用更新的版本
    plan_version bigint NOT NULL,
    -- 成员数上限，NULL 表示不限
    member_quota integer,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE tenant_plan IS '租户 Plan 副本：据 TenantPlanChanged 维护，没有记录的租户不受套餐限制';
COMMENT ON COLUMN tenant_plan.tenant_id IS '租户 ID';
COMMENT ON COLUMN tenant_plan.plan_id IS 'Plan ID，取自 platform.plan';
COMMENT ON COLUMN tenant_plan.plan_version IS '已应用的 Plan 版本，用于丢弃乱序送达的旧变更';
COMMENT ON COLUMN tenant_plan.member_quota IS '成员数上限，NULL 表示不限';
COMMENT ON COLUMN tenant_plan.created_at IS '创建时间';
COMMENT ON COLUMN tenant_plan.updated_at IS '最后更新时间';
COMMENT ON COLUMN tenant_plan.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN tenant_plan.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN tenant_plan.deleted IS '逻辑删除标记，true 表示对常规查询不可见';

-- Feature Pack 中的权限码。纯关联表，不带标准列；随每次变更整体替换。
-- 不引用 permission 表：Plan 可以包含已下线的编码，业务服务重新上报后随之恢复。
CREATE TABLE tenant_plan_permission (
    tenant_id uuid NOT NULL REFERENCES tenant_plan(tenant_id),
    code varchar(128) NOT NULL,
    PRIMARY KEY (tenant_id, code)
);
COMMENT ON TABLE tenant_plan_permission IS '租户 Plan Feature Pack 中的权限码，随每次变更整体替换';
COMMENT ON COLUMN tenant_plan_permission.tenant_id IS '租户 ID';
COMMENT ON COLUMN tenant_plan_permission.code IS '权限码，可包含已下线的编码';
