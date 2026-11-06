-- 仅供成员管理集成测试：一个租户中的 Owner、Tenant Admin 与三个普通成员，各用例改变状态的成员互不相同。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-0000000000d0', '成员测试租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    (CAST('019a0110-0000-7000-8000-0000000000d1' AS uuid), 'memberowner', 'memberowner@example.com', '成员测试所有者'),
    (CAST('019a0110-0000-7000-8000-0000000000d3' AS uuid), 'memberadmin', 'memberadmin@example.com', '成员测试管理员'),
    (CAST('019a0110-0000-7000-8000-0000000000d5' AS uuid), 'membersuspend', 'membersuspend@example.com', '待停用成员'),
    (CAST('019a0110-0000-7000-8000-0000000000d7' AS uuid), 'memberleave', 'memberleave@example.com', '主动退出成员'),
    (CAST('019a0110-0000-7000-8000-0000000000d9' AS uuid), 'membertarget', 'membertarget@example.com', '受让成员')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-0000000000d2', '019a0110-0000-7000-8000-0000000000d1', '019a0110-0000-7000-8000-0000000000d0'),
('019a0110-0000-7000-8000-0000000000d4', '019a0110-0000-7000-8000-0000000000d3', '019a0110-0000-7000-8000-0000000000d0'),
('019a0110-0000-7000-8000-0000000000d6', '019a0110-0000-7000-8000-0000000000d5', '019a0110-0000-7000-8000-0000000000d0'),
('019a0110-0000-7000-8000-0000000000d8', '019a0110-0000-7000-8000-0000000000d7', '019a0110-0000-7000-8000-0000000000d0'),
('019a0110-0000-7000-8000-0000000000da', '019a0110-0000-7000-8000-0000000000d9', '019a0110-0000-7000-8000-0000000000d0')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0110-0000-7000-8000-0000000000db', '019a0110-0000-7000-8000-0000000000d0', 'owner', '所有者', true),
('019a0110-0000-7000-8000-0000000000dc', '019a0110-0000-7000-8000-0000000000d0', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id, owner) VALUES
('019a0110-0000-7000-8000-0000000000d2', '019a0110-0000-7000-8000-0000000000db', true),
('019a0110-0000-7000-8000-0000000000d4', '019a0110-0000-7000-8000-0000000000dc', false)
ON CONFLICT DO NOTHING;
