-- 恢复测试租户未登记 Plan 的初始状态，仅在隔离测试库执行。
DELETE FROM tenant_plan_permission WHERE tenant_id = '019a0110-0000-7000-8000-0000000000c1';
DELETE FROM tenant_plan_sub_app WHERE tenant_id = '019a0110-0000-7000-8000-0000000000c1';
DELETE FROM tenant_plan WHERE tenant_id = '019a0110-0000-7000-8000-0000000000c1';
