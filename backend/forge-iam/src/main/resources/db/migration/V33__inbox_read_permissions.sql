-- 读取入口从 Membership 认证升级为显式 Permission Code，保持已有角色和无角色成员的读取能力。
INSERT INTO permission (id, code, service, name, group_name, realm)
SELECT CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
           PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
           FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid), p.code, 'forge-iam', p.name, '消息读取', 'tenant'
FROM (VALUES ('todo:read', '查看待办'), ('notification:read', '查看通知'), ('announcement:read', '查看公告')) AS p (code, name)
ON CONFLICT (code) WHERE NOT deleted DO NOTHING;

-- 无角色成员使用普通自定义角色，管理员之后可以通过现有角色界面编辑或撤销。
INSERT INTO role (id, tenant_id, code, name, builtin)
SELECT CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
           PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
           FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid), t.id, 'inbox-reader', '消息读取', false
FROM tenant t WHERE NOT t.deleted
AND EXISTS (SELECT 1 FROM membership m WHERE m.tenant_id = t.id AND NOT m.deleted
    AND NOT EXISTS (SELECT 1 FROM membership_role mr JOIN role r ON r.id = mr.role_id
        WHERE mr.membership_id = m.id AND NOT r.deleted))
ON CONFLICT (tenant_id, code) WHERE NOT deleted DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE NOT r.builtin AND NOT r.deleted AND NOT p.deleted
AND p.code IN ('todo:read', 'notification:read', 'announcement:read')
ON CONFLICT DO NOTHING;

INSERT INTO membership_role (membership_id, role_id)
SELECT m.id, r.id FROM membership m JOIN role r ON r.tenant_id = m.tenant_id AND r.code = 'inbox-reader'
WHERE NOT m.deleted AND NOT r.deleted
AND NOT EXISTS (SELECT 1 FROM membership_role mr JOIN role held ON held.id = mr.role_id
    WHERE mr.membership_id = m.id AND NOT held.deleted)
ON CONFLICT DO NOTHING;

-- 同时补齐 IAM 的 Plan 副本；platform 主表由它自己的新增迁移补齐，后续 PlanChanged 不会回退。
INSERT INTO tenant_plan_permission (tenant_id, code)
SELECT tp.tenant_id, p.code FROM tenant_plan tp
CROSS JOIN (VALUES ('todo:read'), ('notification:read'), ('announcement:read')) AS p (code)
WHERE NOT tp.deleted ON CONFLICT DO NOTHING;
