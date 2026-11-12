-- 只在 run.sh 为本次验收创建的隔离容器中执行。
BEGIN;
SET search_path = iam, extensions;
INSERT INTO operator (id, account, display_name, password_hash)
VALUES ('019a2525-0000-7000-8000-000000000071', 'e2e-onboarding', 'E2E 接入运营',
        '$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO platform_role (id, code, name)
VALUES ('019a2525-0000-7000-8000-000000000072', 'e2e-onboarding', 'E2E 接入管理');
INSERT INTO operator_role (operator_id, role_id)
VALUES ('019a2525-0000-7000-8000-000000000071', '019a2525-0000-7000-8000-000000000072');
INSERT INTO platform_role_permission (role_id, permission_id)
SELECT '019a2525-0000-7000-8000-000000000072', id FROM permission
WHERE code IN ('platform:service-credential:manage', 'platform:onboarding:read')
  AND realm = 'platform' AND NOT deleted;
COMMIT;
