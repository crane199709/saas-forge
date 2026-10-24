-- 只由显式 demo profile 加载，避免共享环境出现已知密码。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-000000000001', '演示租户') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
VALUES ('019a0110-0000-7000-8000-000000000002', 'demo', 'demo@example.com', '演示身份', '$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.') ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a0110-0000-7000-8000-000000000003', '019a0110-0000-7000-8000-000000000002', '019a0110-0000-7000-8000-000000000001') ON CONFLICT DO NOTHING;
