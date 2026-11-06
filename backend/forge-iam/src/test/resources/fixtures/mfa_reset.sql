-- 仅供 MFA Reset 集成测试：每个用例一个独立身份或运营账号；密码与演示身份、演示运营相同。插入须可重复执行。
-- 被重置的身份同时有账号、邮箱与手机号，供按三种登录标识查找；均为演示租户成员。
INSERT INTO identity (id, account, email, phone, display_name, password_hash)
SELECT v.id::uuid, v.account, v.account || '@example.com', v.phone, v.name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    ('019a0110-0000-7000-8000-000000000501', 'reset-lookup', '13900000501', '查找身份'),
    ('019a0110-0000-7000-8000-000000000502', 'reset-target', '13900000502', '重置身份')) AS v (id, account, phone, name)
WHERE i.account = 'demo'
ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id, status) VALUES
('019a0110-0000-7000-8000-000000000511', '019a0110-0000-7000-8000-000000000501', '019a0110-0000-7000-8000-000000000001', 'active'),
('019a0110-0000-7000-8000-000000000512', '019a0110-0000-7000-8000-000000000502', '019a0110-0000-7000-8000-000000000001', 'active')
ON CONFLICT DO NOTHING;
-- op-reset-holder 持有只含 platform:identity-mfa:reset 的平台角色；op-reset-plain 没有任何平台角色；op-reset-target 是被重置的运营账号。
INSERT INTO operator (id, account, display_name, password_hash)
SELECT v.id::uuid, v.account, v.account, o.password_hash
FROM operator o CROSS JOIN (VALUES
    ('019a0110-0000-7000-8000-000000000521', 'op-reset-holder'),
    ('019a0110-0000-7000-8000-000000000522', 'op-reset-plain'),
    ('019a0110-0000-7000-8000-000000000523', 'op-reset-target')) AS v (id, account)
WHERE o.account = 'operator'
ON CONFLICT DO NOTHING;
INSERT INTO platform_role (id, code, name)
VALUES ('019a0110-0000-7000-8000-000000000531', 'mfa-resetter', 'MFA 重置')
ON CONFLICT DO NOTHING;
INSERT INTO platform_role_permission (role_id, permission_id)
SELECT '019a0110-0000-7000-8000-000000000531', id FROM permission
WHERE code = 'platform:identity-mfa:reset' AND NOT deleted
ON CONFLICT DO NOTHING;
INSERT INTO operator_role (operator_id, role_id)
VALUES ('019a0110-0000-7000-8000-000000000521', '019a0110-0000-7000-8000-000000000531')
ON CONFLICT DO NOTHING;
