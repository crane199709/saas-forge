-- 仅供 Password Policy 与自助改密码、忘记密码集成测试：每个用例一个独立账号，改密码互不影响。插入须可重复执行。
-- 身份均为演示租户成员、密码与演示身份相同；pwd-legacy 的存量密码是弱密码 password。运营账号的密码与演示运营相同。
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id::uuid, v.account, v.account || '@example.com', v.account, i.password_hash
FROM identity i CROSS JOIN (VALUES
    ('019a0110-0000-7000-8000-000000000401', 'pwd-policy'),
    ('019a0110-0000-7000-8000-000000000402', 'pwd-change'),
    ('019a0110-0000-7000-8000-000000000403', 'pwd-forgot'),
    ('019a0110-0000-7000-8000-000000000404', 'pwd-forgot-mfa')) AS v (id, account)
WHERE i.account = 'demo'
ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash) VALUES
('019a0110-0000-7000-8000-000000000405', 'pwd-legacy', 'pwd-legacy@example.com', 'pwd-legacy',
 '$2y$10$YJd/ZLjxxyrvc7JT4CcVmem/GUBGNOTqcE1p5kYWwYLPV29dgIkRm')
ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id, status) VALUES
('019a0110-0000-7000-8000-000000000411', '019a0110-0000-7000-8000-000000000401', '019a0110-0000-7000-8000-000000000001', 'active'),
('019a0110-0000-7000-8000-000000000412', '019a0110-0000-7000-8000-000000000402', '019a0110-0000-7000-8000-000000000001', 'active'),
('019a0110-0000-7000-8000-000000000413', '019a0110-0000-7000-8000-000000000403', '019a0110-0000-7000-8000-000000000001', 'active'),
('019a0110-0000-7000-8000-000000000414', '019a0110-0000-7000-8000-000000000404', '019a0110-0000-7000-8000-000000000001', 'active'),
('019a0110-0000-7000-8000-000000000415', '019a0110-0000-7000-8000-000000000405', '019a0110-0000-7000-8000-000000000001', 'active')
ON CONFLICT DO NOTHING;
INSERT INTO operator (id, account, display_name, password_hash)
SELECT v.id::uuid, v.account, v.account, o.password_hash
FROM operator o CROSS JOIN (VALUES
    ('019a0110-0000-7000-8000-000000000421', 'pwd-operator'),
    ('019a0110-0000-7000-8000-000000000422', 'pwd-operator-reset')) AS v (id, account)
WHERE o.account = 'operator'
ON CONFLICT DO NOTHING;
