-- 导航布局集成测试：两个租户各有一名 Tenant Admin，租户 A 另有一名没有角色的成员与一名只有 iam:menu:manage 的成员；另有一名没有平台角色的 Operator。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES
('019a0200-0000-7000-8000-000000000001', '导航布局租户 A'),
('019a0200-0000-7000-8000-000000000002', '导航布局租户 B') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    (CAST('019a0200-0000-7000-8000-0000000000a1' AS uuid), 'navadmina', 'navadmina@example.com', '导航管理员 A'),
    (CAST('019a0200-0000-7000-8000-0000000000a3' AS uuid), 'navmembera', 'navmembera@example.com', '导航成员 A'),
    (CAST('019a0200-0000-7000-8000-0000000000a6' AS uuid), 'naveditora', 'naveditora@example.com', '导航编辑 A'),
    (CAST('019a0200-0000-7000-8000-0000000000b1' AS uuid), 'navadminb', 'navadminb@example.com', '导航管理员 B')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0200-0000-7000-8000-0000000000a2', '019a0200-0000-7000-8000-0000000000a1', '019a0200-0000-7000-8000-000000000001'),
('019a0200-0000-7000-8000-0000000000a4', '019a0200-0000-7000-8000-0000000000a3', '019a0200-0000-7000-8000-000000000001'),
('019a0200-0000-7000-8000-0000000000a7', '019a0200-0000-7000-8000-0000000000a6', '019a0200-0000-7000-8000-000000000001'),
('019a0200-0000-7000-8000-0000000000b2', '019a0200-0000-7000-8000-0000000000b1', '019a0200-0000-7000-8000-000000000002')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0200-0000-7000-8000-0000000000a5', '019a0200-0000-7000-8000-000000000001', 'tenant-admin', '租户管理员', true),
('019a0200-0000-7000-8000-0000000000b5', '019a0200-0000-7000-8000-000000000002', 'tenant-admin', '租户管理员', true),
('019a0200-0000-7000-8000-0000000000a8', '019a0200-0000-7000-8000-000000000001', 'menu-editor', '菜单管理员', false)
ON CONFLICT DO NOTHING;
-- iam 的权限码在启动时登记，早于本脚本执行。
INSERT INTO role_permission (role_id, permission_id)
SELECT '019a0200-0000-7000-8000-0000000000a8', id FROM permission WHERE code = 'iam:menu:manage'
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id) VALUES
('019a0200-0000-7000-8000-0000000000a2', '019a0200-0000-7000-8000-0000000000a5'),
('019a0200-0000-7000-8000-0000000000b2', '019a0200-0000-7000-8000-0000000000b5'),
('019a0200-0000-7000-8000-0000000000a7', '019a0200-0000-7000-8000-0000000000a8')
ON CONFLICT DO NOTHING;
INSERT INTO operator (id, account, display_name, password_hash)
SELECT '019a0200-0000-7000-8000-0000000000f2', 'navigation-limited', '无菜单权限运营', password_hash
FROM operator WHERE account = 'operator' AND NOT deleted ON CONFLICT DO NOTHING;
