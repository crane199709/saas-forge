-- 只由显式 demo profile 加载，避免共享环境出现已知密码。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-000000000001', '演示租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
VALUES ('019a0110-0000-7000-8000-000000000002', 'demo', 'demo@example.com', '演示身份', '$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.') ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a0110-0000-7000-8000-000000000003', '019a0110-0000-7000-8000-000000000002', '019a0110-0000-7000-8000-000000000001') ON CONFLICT DO NOTHING;
-- 新建库时演示租户晚于 V7 的补齐插入，预置角色在这里补上；演示身份是该租户的 Owner。
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0110-0000-7000-8000-0000000000e1', '019a0110-0000-7000-8000-000000000001', 'owner', '所有者', true),
('019a0110-0000-7000-8000-0000000000e2', '019a0110-0000-7000-8000-000000000001', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id, owner)
SELECT '019a0110-0000-7000-8000-000000000003', id, true FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'owner' AND NOT deleted
ON CONFLICT DO NOTHING;
