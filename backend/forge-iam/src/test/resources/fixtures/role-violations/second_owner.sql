-- 演示租户的 Owner 已由演示身份持有，再授予另一成员必须失败。
INSERT INTO membership_role (membership_id, role_id, owner)
SELECT '019a0110-0000-7000-8000-0000000000a4', id, true FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'owner' AND NOT deleted;
