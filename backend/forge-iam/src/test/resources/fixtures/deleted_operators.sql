-- 仅供隔离集成测试：已逻辑删除的运营账号，密码与演示运营相同。多个测试共用，插入须可重复执行。
INSERT INTO operator (id, account, display_name, password_hash, deleted)
SELECT '019a0110-0000-7000-8000-0000000000f2', 'gone-operator', '已删除运营', password_hash, true
FROM operator WHERE account = 'operator' ON CONFLICT DO NOTHING;
