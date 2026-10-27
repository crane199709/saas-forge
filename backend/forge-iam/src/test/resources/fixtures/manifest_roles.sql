-- 仅供资源清单集成测试：演示租户中一个 Tenant Admin 与一个没有任何角色的成员。插入须可重复执行。
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-0000000000a1', 'admin', 'admin@example.com', '管理员身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-0000000000a2', 'plain', 'plain@example.com', '普通身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-0000000000a3', '019a0110-0000-7000-8000-0000000000a1', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000000a4', '019a0110-0000-7000-8000-0000000000a2', '019a0110-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
SELECT '019a0110-0000-7000-8000-0000000000a3', id FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'tenant-admin' AND NOT deleted
ON CONFLICT DO NOTHING;
-- 尚未开通预置角色的新租户
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-0000000000a5', '待开通租户') ON CONFLICT DO NOTHING;
