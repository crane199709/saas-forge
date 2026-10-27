-- 自定义角色不能占用预置角色的编码。
INSERT INTO role (id, tenant_id, code, name, builtin)
VALUES ('019a0110-0000-7000-8000-0000000000a6', '019a0110-0000-7000-8000-0000000000a5', 'owner', '冒名所有者', false);
