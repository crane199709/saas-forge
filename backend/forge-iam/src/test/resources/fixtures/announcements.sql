-- 公告权限验收所需的普通 Operator 与已离开的 Membership，测试通过真实签名 Token 访问 HTTP。
INSERT INTO operator (id, account, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-0000000003d1', 'announcement-limited', '无公告权限运营', password_hash
FROM operator WHERE account = 'operator' AND NOT deleted ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-0000000003d2', 'announcement-left', 'announcement-left@example.com', '已离开成员', password_hash
FROM identity WHERE account = 'demo' AND NOT deleted ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id, status)
VALUES ('019a0110-0000-7000-8000-0000000003d3', '019a0110-0000-7000-8000-0000000003d2', '019a0110-0000-7000-8000-000000000001', 'left')
ON CONFLICT DO NOTHING;
