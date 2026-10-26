-- 仅供隔离集成测试：多 Membership 的密码与演示身份相同。多个测试共用，插入须可重复执行。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-000000000004', '第二租户') ON CONFLICT DO NOTHING;
INSERT INTO tenant (id, name, status) VALUES ('019a0110-0000-7000-8000-000000000008', '停用租户', 'suspended') ON CONFLICT DO NOTHING;
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-000000000009', '第三租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-000000000005', 'multi', 'multi@example.com', '多成员身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-00000000000c', 'lonely', 'lonely@example.com', '无租户身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id, status) VALUES
('019a0110-0000-7000-8000-000000000006', '019a0110-0000-7000-8000-000000000005', '019a0110-0000-7000-8000-000000000001', 'active'),
('019a0110-0000-7000-8000-000000000007', '019a0110-0000-7000-8000-000000000005', '019a0110-0000-7000-8000-000000000004', 'active'),
-- 所在租户已停用，Membership 自身仍为 active
('019a0110-0000-7000-8000-00000000000a', '019a0110-0000-7000-8000-000000000005', '019a0110-0000-7000-8000-000000000008', 'active'),
-- Membership 自身非 active
('019a0110-0000-7000-8000-00000000000b', '019a0110-0000-7000-8000-000000000005', '019a0110-0000-7000-8000-000000000009', 'suspended')
ON CONFLICT DO NOTHING;
