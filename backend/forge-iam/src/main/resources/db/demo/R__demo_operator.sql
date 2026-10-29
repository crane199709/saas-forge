-- 只由显式 demo profile 加载，避免共享环境出现已知密码。演示运营为超级管理员。
INSERT INTO operator (id, account, display_name, password_hash)
VALUES ('019a0110-0000-7000-8000-0000000000f1', 'operator', '演示运营', '$2a$10$CdLQEZo4NbL9hBp6kvSU7.dVuyo0P9wUVyiaDF.ctjSD4HBam3v6.') ON CONFLICT DO NOTHING;
INSERT INTO operator_role (operator_id, role_id)
SELECT '019a0110-0000-7000-8000-0000000000f1', id FROM platform_role WHERE code = 'super-admin' AND NOT deleted
ON CONFLICT DO NOTHING;
