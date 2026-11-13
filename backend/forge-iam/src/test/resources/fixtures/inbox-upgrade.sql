-- V32 数据：既有自定义角色成员、无角色成员和已登记 Plan。
INSERT INTO role (id, tenant_id, code, name, builtin)
VALUES ('019a0120-0000-7000-8000-0000000000d1', '019a0120-0000-7000-8000-0000000000a6', 'legacy-member', '既有角色', false);
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000aa', '019a0120-0000-7000-8000-0000000000d1');
INSERT INTO tenant_plan (tenant_id, plan_id, plan_version)
VALUES ('019a0120-0000-7000-8000-0000000000a0', '019a0120-0000-7000-8000-0000000000d2', 1);
