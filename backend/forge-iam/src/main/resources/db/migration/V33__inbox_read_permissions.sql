-- 读取入口从 Membership 认证升级为显式 Permission Code，保持已有角色和无角色成员的读取能力。
INSERT INTO permission (id, code, service, name, group_name, realm)
SELECT CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
           PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
           FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid), p.code, 'forge-iam', p.name, '消息读取', 'tenant'
FROM (VALUES ('todo:read', '查看待办'), ('notification:read', '查看通知'), ('announcement:read', '查看公告')) AS p (code, name)
ON CONFLICT (code) WHERE NOT deleted DO NOTHING;

-- 初始读取角色的身份由 IAM 内部映射保存，不能把租户自建的同编码角色当成系统创建的角色。
-- 纯授权关联表，沿用 role 的 IAM 全局表隔离例外（ADR-0001），读写均显式限定 tenant_id。
CREATE TABLE tenant_initial_read_role (
    tenant_id uuid PRIMARY KEY REFERENCES tenant(id),
    role_id uuid NOT NULL
);
COMMENT ON TABLE tenant_initial_read_role IS 'IAM 为租户创建的初始消息读取角色，角色仍可由管理员编辑和撤销';
COMMENT ON COLUMN tenant_initial_read_role.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN tenant_initial_read_role.role_id IS 'IAM 创建的初始读取角色 ID，不通过角色编码识别';

-- 同一迁移事务内先保存新角色 ID，再创建角色和外键，避免按不可信的现有编码回查角色身份。
INSERT INTO tenant_initial_read_role (tenant_id, role_id)
SELECT t.id, CAST(encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
           PLACING substring(int8send(CAST(floor(extract(epoch FROM clock_timestamp()) * 1000) AS bigint)) FROM 3)
           FROM 1 FOR 6), 52, 1), 53, 1), 'hex') AS uuid)
FROM tenant t WHERE NOT t.deleted
AND EXISTS (SELECT 1 FROM membership m WHERE m.tenant_id = t.id AND NOT m.deleted
    AND NOT EXISTS (SELECT 1 FROM membership_role mr JOIN role r ON r.id = mr.role_id
        WHERE mr.membership_id = m.id AND NOT r.deleted));

INSERT INTO role (id, tenant_id, code, name, builtin)
SELECT initial.role_id, initial.tenant_id,
    CASE WHEN EXISTS (SELECT 1 FROM role r WHERE r.tenant_id = initial.tenant_id
        AND r.code = 'inbox-reader' AND NOT r.deleted)
        THEN 'inbox-reader-' || CAST(initial.role_id AS varchar(36)) ELSE 'inbox-reader' END,
    '消息读取', false
FROM tenant_initial_read_role initial;
ALTER TABLE tenant_initial_read_role ADD FOREIGN KEY (role_id) REFERENCES role(id);

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE NOT r.builtin AND NOT r.deleted AND NOT p.deleted
AND p.code IN ('todo:read', 'notification:read', 'announcement:read')
ON CONFLICT DO NOTHING;

INSERT INTO membership_role (membership_id, role_id)
SELECT m.id, r.id FROM membership m
JOIN tenant_initial_read_role initial ON initial.tenant_id = m.tenant_id
JOIN role r ON r.id = initial.role_id AND r.tenant_id = m.tenant_id
WHERE NOT m.deleted AND NOT r.deleted
AND NOT EXISTS (SELECT 1 FROM membership_role mr JOIN role held ON held.id = mr.role_id
    WHERE mr.membership_id = m.id AND NOT held.deleted)
ON CONFLICT DO NOTHING;

-- 同时补齐 IAM 的 Plan 副本；platform 主表由它自己的新增迁移补齐，后续 PlanChanged 不会回退。
INSERT INTO tenant_plan_permission (tenant_id, code)
SELECT tp.tenant_id, p.code FROM tenant_plan tp
CROSS JOIN (VALUES ('todo:read'), ('notification:read'), ('announcement:read')) AS p (code)
WHERE NOT tp.deleted ON CONFLICT DO NOTHING;
