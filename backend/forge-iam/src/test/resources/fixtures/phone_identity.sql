-- 仅供隔离集成测试：只有手机号、没有邮箱与 Membership 的身份，密码与演示身份相同。插入须可重复执行。
INSERT INTO identity (id, account, phone, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-000000000030', 'mobile', '13900000001', '手机身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
