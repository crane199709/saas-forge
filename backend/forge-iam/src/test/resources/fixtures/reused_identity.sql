-- 仅供隔离集成测试：账号、邮箱与 Membership 被逻辑删除后重新创建。已删除的身份使用演示运营的密码，
-- 新身份使用演示身份的密码，邮箱只差大小写。多个测试共用，插入须可重复执行。
INSERT INTO identity (id, account, email, display_name, password_hash, deleted)
SELECT '019a0110-0000-7000-8000-000000000027', 'reborn', 'reborn@example.com', '已删除的旧身份', password_hash, true
FROM operator WHERE account = 'operator' ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-000000000028', 'reborn', 'Reborn@example.com', '重新创建的身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id, deleted) VALUES
('019a0110-0000-7000-8000-000000000029', '019a0110-0000-7000-8000-000000000028', '019a0110-0000-7000-8000-000000000001', true),
('019a0110-0000-7000-8000-00000000002a', '019a0110-0000-7000-8000-000000000028', '019a0110-0000-7000-8000-000000000001', false)
ON CONFLICT DO NOTHING;
