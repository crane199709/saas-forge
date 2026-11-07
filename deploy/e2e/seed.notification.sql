-- 仅在 run.sh 本次隔离容器内、默认 seed 后执行，不创建任何收件箱或待办记录。
BEGIN;
SET search_path = iam, extensions;
INSERT INTO role_permission (role_id,permission_id)
SELECT '019a2525-0000-7000-8000-000000000041',id FROM permission
WHERE code = 'order:update' AND realm = 'tenant' AND NOT deleted;
INSERT INTO operator (id,account,display_name,password_hash)
VALUES ('019a2525-0000-7000-8000-000000000061','e2e-announcement','E2E 公告运营','$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO platform_role (id,code,name)
VALUES ('019a2525-0000-7000-8000-000000000062','e2e-announcement','E2E 公告发布');
INSERT INTO operator_role (operator_id,role_id)
VALUES ('019a2525-0000-7000-8000-000000000061','019a2525-0000-7000-8000-000000000062');
INSERT INTO platform_role_permission (role_id,permission_id)
SELECT '019a2525-0000-7000-8000-000000000062',id FROM permission
WHERE code = 'platform:announcement:manage' AND realm = 'platform' AND NOT deleted;
COMMIT;
