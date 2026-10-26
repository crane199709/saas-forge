-- 仅供隔离集成测试：多 Membership 的密码与演示身份相同。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-000000000004', '第二租户');
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-000000000005', 'multi', 'multi@example.com', '多成员身份', password_hash
FROM identity WHERE account = 'demo';
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-000000000006', '019a0110-0000-7000-8000-000000000005', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-000000000007', '019a0110-0000-7000-8000-000000000005', '019a0110-0000-7000-8000-000000000004');
