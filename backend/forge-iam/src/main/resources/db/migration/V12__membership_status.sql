-- 成员管理（工单 17）：Membership 状态收敛为四种。停用沿用工单 07 已写入的 suspended；
-- 移除与主动退出都改为 left 而不是逻辑删除，成员列表仍要展示「已离开」，再次邀请时同一行回到 invited。
ALTER TABLE membership ADD CONSTRAINT membership_status_check
    CHECK (status IN ('invited', 'active', 'suspended', 'left'));
