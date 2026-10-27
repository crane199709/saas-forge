-- 以 owner = false 授予 Owner 角色以绕过唯一索引，必须被复合外键拒绝。
INSERT INTO membership_role (membership_id, role_id, owner)
SELECT '019a0110-0000-7000-8000-0000000000a4', id, false FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'owner' AND NOT deleted;
