-- 仅供组织模型集成测试：演示租户中一个 Tenant Admin 与一个普通成员，另一个租户中一个 Tenant Admin。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-0000000000b1', '组织测试租户 B') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    ('019a0110-0000-7000-8000-0000000000b2'::uuid, 'orgadmin', 'orgadmin@example.com', '组织管理员'),
    ('019a0110-0000-7000-8000-0000000000b4'::uuid, 'orgmember', 'orgmember@example.com', '组织成员'),
    ('019a0110-0000-7000-8000-0000000000b6'::uuid, 'orgadminb', 'orgadminb@example.com', '租户 B 管理员')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-0000000000b3', '019a0110-0000-7000-8000-0000000000b2', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000000b5', '019a0110-0000-7000-8000-0000000000b4', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000000b7', '019a0110-0000-7000-8000-0000000000b6', '019a0110-0000-7000-8000-0000000000b1')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0110-0000-7000-8000-0000000000b8', '019a0110-0000-7000-8000-0000000000b1', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
SELECT '019a0110-0000-7000-8000-0000000000b3', id FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'tenant-admin' AND NOT deleted
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0110-0000-7000-8000-0000000000b7', '019a0110-0000-7000-8000-0000000000b8')
ON CONFLICT DO NOTHING;
