-- 仅供 Operator MFA 集成测试：每个用例一个独立运营账号，Authenticator 状态互不影响；密码与演示运营相同。插入须可重复执行。
INSERT INTO operator (id, account, display_name, password_hash)
SELECT v.id::uuid, v.account, v.account, o.password_hash
FROM operator o CROSS JOIN (VALUES
    ('019a0110-0000-7000-8000-000000000401', 'op-mfa-unbound'),
    ('019a0110-0000-7000-8000-000000000402', 'op-mfa-login'),
    ('019a0110-0000-7000-8000-000000000403', 'op-mfa-recovery'),
    ('019a0110-0000-7000-8000-000000000404', 'op-mfa-unbind'),
    ('019a0110-0000-7000-8000-000000000405', 'op-mfa-forced'),
    ('019a0110-0000-7000-8000-000000000406', 'op-mfa-keep'),
    ('019a0110-0000-7000-8000-000000000407', 'op-mfa-replace'),
    ('019a0110-0000-7000-8000-000000000408', 'op-mfa-reset')) AS v (id, account)
WHERE o.account = 'operator'
ON CONFLICT DO NOTHING;
