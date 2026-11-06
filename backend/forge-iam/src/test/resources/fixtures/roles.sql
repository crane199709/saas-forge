-- 仅供角色管理集成测试：演示租户中一个 Tenant Admin 与一个普通成员，另一个租户中一个 Tenant Admin。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-0000000000c1', '角色测试租户 B') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    (CAST('019a0110-0000-7000-8000-0000000000c2' AS uuid), 'roleadmin', 'roleadmin@example.com', '角色管理员'),
    (CAST('019a0110-0000-7000-8000-0000000000c4' AS uuid), 'rolemember', 'rolemember@example.com', '角色成员'),
    (CAST('019a0110-0000-7000-8000-0000000000c6' AS uuid), 'roleadminb', 'roleadminb@example.com', '租户 B 管理员')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-0000000000c3', '019a0110-0000-7000-8000-0000000000c2', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000000c5', '019a0110-0000-7000-8000-0000000000c4', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000000c7', '019a0110-0000-7000-8000-0000000000c6', '019a0110-0000-7000-8000-0000000000c1')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0110-0000-7000-8000-0000000000c8', '019a0110-0000-7000-8000-0000000000c1', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
SELECT '019a0110-0000-7000-8000-0000000000c3', id FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'tenant-admin' AND NOT deleted
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0110-0000-7000-8000-0000000000c7', '019a0110-0000-7000-8000-0000000000c8')
ON CONFLICT DO NOTHING;
