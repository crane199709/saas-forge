INSERT INTO tenant (id, name) VALUES ('019a0120-0000-7000-8000-0000000000d0', 'AI 套餐测试租户');
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT CAST('019a0120-0000-7000-8000-0000000000d1' AS uuid), 'aiplanadmin', 'aiplanadmin@example.com', 'AI 套餐管理员', password_hash
FROM identity WHERE account = 'demo';
INSERT INTO membership (id, identity_id, tenant_id)
VALUES ('019a0120-0000-7000-8000-0000000000d2', '019a0120-0000-7000-8000-0000000000d1', '019a0120-0000-7000-8000-0000000000d0');
INSERT INTO role (id, tenant_id, code, name, builtin)
VALUES ('019a0120-0000-7000-8000-0000000000d3', '019a0120-0000-7000-8000-0000000000d0', 'tenant-admin', '租户管理员', true);
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0120-0000-7000-8000-0000000000d2', '019a0120-0000-7000-8000-0000000000d3');
