-- V32 数据：既有自定义角色成员、无角色成员和已登记 Plan。
INSERT INTO role (id, tenant_id, code, name, builtin)
VALUES ('019a0120-0000-7000-8000-0000000000d1', '019a0120-0000-7000-8000-0000000000a6', 'legacy-member', '既有角色', false);
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000aa', '019a0120-0000-7000-8000-0000000000d1');
INSERT INTO tenant_plan (tenant_id, plan_id, plan_version)
VALUES ('019a0120-0000-7000-8000-0000000000a0', '019a0120-0000-7000-8000-0000000000d2', 1);

-- inbox-reader 在 V32 从未保留；原角色已有管理权限与成员，升级不能把它自动分配给无角色成员。
INSERT INTO role (id, tenant_id, code, name, builtin)
VALUES ('019a0120-0000-7000-8000-0000000000d3', '019a0120-0000-7000-8000-0000000000a0', 'inbox-reader', '原有管理角色', false);
INSERT INTO permission (id, code, service, name, group_name, realm)
VALUES ('019a0120-0000-7000-8000-0000000000d5', 'iam:role:manage', 'forge-iam', '管理角色', '角色', 'tenant')
ON CONFLICT (code) WHERE NOT deleted DO NOTHING;
INSERT INTO role_permission (role_id, permission_id)
SELECT CAST('019a0120-0000-7000-8000-0000000000d3' AS uuid), id FROM permission
WHERE code = 'iam:role:manage' AND NOT deleted;
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a0120-0000-7000-8000-0000000000d4', '019a0110-0000-7000-8000-000000000002', '019a0120-0000-7000-8000-0000000000a0');
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000d4', '019a0120-0000-7000-8000-0000000000d3');
