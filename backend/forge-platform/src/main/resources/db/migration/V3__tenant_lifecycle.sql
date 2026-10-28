-- Tenant Lifecycle（工单 15）：状态机、试用/订购到期提醒与删除冷静期。
ALTER TABLE tenant
    -- 每次状态变更加 1，随 TenantStatusChanged 发布，订阅方据此丢弃乱序送达的旧变更；也用作迁移的乐观锁
    ADD COLUMN status_version bigint NOT NULL DEFAULT 0,
    -- 试用或订购的到期时间；到期提醒在此之前的提醒窗口内发送一次，expiry_reminded_at 记录已发送
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN expiry_reminded_at timestamptz,
    -- 删除进入冷静期时记录：删除前的状态用于撤回，purge_at 为冷静期结束时间，purged_at 为已发布 TenantDeleted 的时间
    ADD COLUMN status_before_deletion varchar(20),
    ADD COLUMN purge_at timestamptz,
    ADD COLUMN purged_at timestamptz,
    ADD CONSTRAINT tenant_status_check
        CHECK (status IN ('pending', 'trial', 'active', 'suspended', 'expired', 'archived', 'deleted')),
    ADD CONSTRAINT tenant_purge_at_check CHECK ((status = 'deleted') = (purge_at IS NOT NULL)),
    ADD CONSTRAINT tenant_status_before_deletion_check
        CHECK ((status = 'deleted') = (status_before_deletion IS NOT NULL)),
    ADD CONSTRAINT tenant_purged_at_check CHECK (purged_at IS NULL OR status = 'deleted');

-- 定时扫描冷静期到期与即将到期的租户
CREATE INDEX tenant_purge_due_idx ON tenant (purge_at) WHERE purged_at IS NULL AND NOT deleted;
CREATE INDEX tenant_expiry_due_idx ON tenant (expires_at) WHERE expiry_reminded_at IS NULL AND NOT deleted;

-- 状态变更历史，只追加不修改；日志表不带标准列（建表标准列规格）。创建租户也记一条，from_status 为 NULL。
CREATE TABLE tenant_status_change (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    reason varchar(500),
    -- 执行变更的 Operator，系统发起的变更为 NULL
    changed_by uuid,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tenant_status_change_tenant_idx ON tenant_status_change (tenant_id, changed_at);
