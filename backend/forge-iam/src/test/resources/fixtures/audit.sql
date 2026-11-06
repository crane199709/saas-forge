-- 仅供审计集成测试：两个租户各一位 Tenant Admin，租户 A 另有一位没有管理权限的普通成员。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES
('019a0110-0000-7000-8000-000000000210', '审计租户 A'),
('019a0110-0000-7000-8000-000000000220', '审计租户 B')
ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    (CAST('019a0110-0000-7000-8000-000000000211' AS uuid), 'auditadmina', 'auditadmina@example.com', '审计管理员甲'),
    (CAST('019a0110-0000-7000-8000-000000000213' AS uuid), 'auditmember', 'auditmember@example.com', '审计普通成员'),
    (CAST('019a0110-0000-7000-8000-000000000221' AS uuid), 'auditadminb', 'auditadminb@example.com', '审计管理员乙')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-000000000212', '019a0110-0000-7000-8000-000000000211', '019a0110-0000-7000-8000-000000000210'),
('019a0110-0000-7000-8000-000000000214', '019a0110-0000-7000-8000-000000000213', '019a0110-0000-7000-8000-000000000210'),
('019a0110-0000-7000-8000-000000000222', '019a0110-0000-7000-8000-000000000221', '019a0110-0000-7000-8000-000000000220')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0110-0000-7000-8000-000000000215', '019a0110-0000-7000-8000-000000000210', 'tenant-admin', '租户管理员', true),
('019a0110-0000-7000-8000-000000000225', '019a0110-0000-7000-8000-000000000220', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id, owner) VALUES
('019a0110-0000-7000-8000-000000000212', '019a0110-0000-7000-8000-000000000215', false),
('019a0110-0000-7000-8000-000000000222', '019a0110-0000-7000-8000-000000000225', false)
ON CONFLICT DO NOTHING;
