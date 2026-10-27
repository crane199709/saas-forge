-- 仅供隔离集成测试：运营账号被逻辑删除后重新创建。已删除的账号使用演示身份的密码，新账号使用演示运营的密码。
-- 多个测试共用，插入须可重复执行。
INSERT INTO operator (id, account, display_name, password_hash, deleted)
SELECT '019a0110-0000-7000-8000-0000000000f3', 'reborn-operator', '已删除的旧运营', password_hash, true
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO operator (id, account, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-0000000000f4', 'reborn-operator', '重新创建的运营', password_hash
FROM operator WHERE account = 'operator' ON CONFLICT DO NOTHING;
