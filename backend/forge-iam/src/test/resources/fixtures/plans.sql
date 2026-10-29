-- 仅供套餐集成测试：一个租户中的 Tenant Admin 与一个普通成员。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES ('019a0120-0000-7000-8000-0000000000a0', '套餐测试租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    ('019a0120-0000-7000-8000-0000000000a1'::uuid, 'planadmin', 'planadmin@example.com', '套餐测试管理员'),
    ('019a0120-0000-7000-8000-0000000000a3'::uuid, 'planmember', 'planmember@example.com', '套餐测试成员')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0120-0000-7000-8000-0000000000a2', '019a0120-0000-7000-8000-0000000000a1', '019a0120-0000-7000-8000-0000000000a0'),
('019a0120-0000-7000-8000-0000000000a4', '019a0120-0000-7000-8000-0000000000a3', '019a0120-0000-7000-8000-0000000000a0')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0120-0000-7000-8000-0000000000a5', '019a0120-0000-7000-8000-0000000000a0', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000a2', '019a0120-0000-7000-8000-0000000000a5')
ON CONFLICT DO NOTHING;
-- 成员数配额用例使用另一个租户：其 Plan 不影响上面租户「未登记 Plan 时不受限」的断言。
INSERT INTO tenant (id, name) VALUES ('019a0120-0000-7000-8000-0000000000a6', '配额测试租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    ('019a0120-0000-7000-8000-0000000000a7'::uuid, 'quotaadmin', 'quotaadmin@example.com', '配额测试管理员')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0120-0000-7000-8000-0000000000a8', '019a0120-0000-7000-8000-0000000000a7', '019a0120-0000-7000-8000-0000000000a6'),
('019a0120-0000-7000-8000-0000000000aa', '019a0120-0000-7000-8000-0000000000a3', '019a0120-0000-7000-8000-0000000000a6')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0120-0000-7000-8000-0000000000a9', '019a0120-0000-7000-8000-0000000000a6', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000a8', '019a0120-0000-7000-8000-0000000000a9')
ON CONFLICT DO NOTHING;
-- Sub-App 加载用例使用另一个租户：其 Plan 不影响上面租户「未登记 Plan 时不受限」的断言。
INSERT INTO tenant (id, name) VALUES ('019a0120-0000-7000-8000-0000000000c0', 'Sub-App 测试租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0120-0000-7000-8000-0000000000c1'::uuid, 'appadmin', 'appadmin@example.com', 'Sub-App 测试管理员', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a0120-0000-7000-8000-0000000000c2', '019a0120-0000-7000-8000-0000000000c1', '019a0120-0000-7000-8000-0000000000c0')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0120-0000-7000-8000-0000000000c3', '019a0120-0000-7000-8000-0000000000c0', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000c2', '019a0120-0000-7000-8000-0000000000c3')
ON CONFLICT DO NOTHING;
