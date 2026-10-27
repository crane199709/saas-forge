-- 仅供隔离集成测试：已逻辑删除的身份，密码与演示身份相同。多个测试共用，插入须可重复执行。
INSERT INTO identity (id, account, email, display_name, password_hash, deleted)
SELECT '019a0110-0000-7000-8000-000000000020', 'gone', 'gone@example.com', '已删除身份', password_hash, true
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a0110-0000-7000-8000-000000000021', '019a0110-0000-7000-8000-000000000020', '019a0110-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;
