-- 仅在 run.sh 本次创建的隔离 PostgreSQL 执行：有导入权限、没有独立回滚权限。
BEGIN;
SET search_path = iam, extensions;
INSERT INTO identity (id, account, email, display_name, password_hash)
VALUES ('019a2525-0000-7000-8000-000000000081', 'e2e-importer', 'e2e-importer@example.com', 'E2E 导入成员',
        '$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a2525-0000-7000-8000-000000000082', '019a2525-0000-7000-8000-000000000081', '019a2525-0000-7000-8000-000000000001');
INSERT INTO role (id, tenant_id, code, name, builtin)
VALUES ('019a2525-0000-7000-8000-000000000083', '019a2525-0000-7000-8000-000000000001', 'e2e-importer', 'E2E 导入成员', false);
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a2525-0000-7000-8000-000000000082', '019a2525-0000-7000-8000-000000000083');
INSERT INTO role_permission (role_id, permission_id)
SELECT '019a2525-0000-7000-8000-000000000083', id FROM permission
WHERE code IN ('order:read', 'order:import') AND NOT deleted;
INSERT INTO role_data_policy (role_id, resource, scope)
VALUES ('019a2525-0000-7000-8000-000000000083', 'order', 'all');
COMMIT;
