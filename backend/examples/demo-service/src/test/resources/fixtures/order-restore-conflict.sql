-- 恢复不能经创建接口复现的存量编号复用：同一租户的已删除行与有效行共享 Business Number。
SELECT set_config('app.tenant_id', '019a0110-0000-7000-8000-0000000000a3', false);
INSERT INTO orders (id, tenant_id, number, title, amount, status, owner_id, created_at, updated_at, created_by, updated_by, deleted)
VALUES
    (CAST('019a0110-0000-7000-8000-0000000000a4' AS uuid), CAST('019a0110-0000-7000-8000-0000000000a3' AS uuid), 'SO-20261109-0042', '误删编号订单', 42, 'unpaid', CAST('019a0110-0000-7000-8000-000000000003' AS uuid), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, true),
    (CAST('019a0110-0000-7000-8000-0000000000a5' AS uuid), CAST('019a0110-0000-7000-8000-0000000000a3' AS uuid), 'SO-20261109-0042', '占用编号订单', 43, 'unpaid', CAST('019a0110-0000-7000-8000-000000000003' AS uuid), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, false);
SELECT set_config('app.tenant_id', '', false);
