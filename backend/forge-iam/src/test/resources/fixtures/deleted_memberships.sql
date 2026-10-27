-- 仅供隔离集成测试：一个身份在演示租户有可用 Membership，另有一条已删除的 Membership 和一个已删除租户中的 Membership。
-- 依赖 multiple_memberships.sql 中的第二租户。多个测试共用，插入须可重复执行。
INSERT INTO tenant (id, name, deleted) VALUES ('019a0110-0000-7000-8000-000000000022', '已删除租户', true) ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT '019a0110-0000-7000-8000-000000000023', 'half', 'half@example.com', '部分删除身份', password_hash
FROM identity WHERE account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id, deleted) VALUES
('019a0110-0000-7000-8000-000000000024', '019a0110-0000-7000-8000-000000000023', '019a0110-0000-7000-8000-000000000001', false),
-- Membership 自身已删除，status 仍为 active
('019a0110-0000-7000-8000-000000000025', '019a0110-0000-7000-8000-000000000023', '019a0110-0000-7000-8000-000000000004', true),
-- 所在租户已删除，Membership 自身未删除
('019a0110-0000-7000-8000-000000000026', '019a0110-0000-7000-8000-000000000023', '019a0110-0000-7000-8000-000000000022', false)
ON CONFLICT DO NOTHING;
