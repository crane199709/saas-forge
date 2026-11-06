-- 租户副本记录已应用的 Tenant Lifecycle 状态版本（工单 15）：TenantStatusChanged 可能乱序或重复送达，只应用更新的版本。
ALTER TABLE tenant ADD COLUMN status_version bigint NOT NULL DEFAULT 0;
COMMENT ON COLUMN tenant.status_version IS '已应用的 Tenant Lifecycle 状态版本，用于丢弃乱序送达的旧变更';
