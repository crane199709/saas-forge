-- 迁移之后才由测试插入的普通成员需要显式读取授权，沿用生产的普通角色模型。
INSERT INTO role (id, tenant_id, code, name, builtin)
SELECT CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
    PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
    FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid), t.id, 'inbox-reader', '消息读取', false
FROM tenant t WHERE NOT t.deleted ON CONFLICT (tenant_id, code) WHERE NOT deleted DO NOTHING;
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'inbox-reader' AND NOT r.deleted AND NOT p.deleted
AND p.code IN ('todo:read', 'notification:read', 'announcement:read') ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
SELECT m.id, r.id FROM membership m JOIN role r ON r.tenant_id = m.tenant_id AND r.code = 'inbox-reader'
WHERE NOT m.deleted AND NOT r.deleted
AND NOT EXISTS (SELECT 1 FROM membership_role mr JOIN role held ON held.id = mr.role_id
    WHERE mr.membership_id = m.id AND NOT held.deleted)
ON CONFLICT DO NOTHING;
