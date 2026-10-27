-- 先取消预置标记再删除的路径同样被拒绝。
UPDATE role SET builtin = false
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'tenant-admin' AND NOT deleted;
