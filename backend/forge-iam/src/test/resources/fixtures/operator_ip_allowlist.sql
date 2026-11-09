-- 清除只作用于指定 Tenant；同一 Identity 在两个 Tenant 均可读取安全设置。
INSERT INTO tenant (id, name) VALUES
('019a0192-0000-7000-8000-000000000001', '紧急清除租户 A'),
('019a0192-0000-7000-8000-000000000002', '紧急清除租户 B')
ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0192-0000-7000-8000-000000000011', '019a0110-0000-7000-8000-000000000002', '019a0192-0000-7000-8000-000000000001'),
('019a0192-0000-7000-8000-000000000012', '019a0110-0000-7000-8000-000000000002', '019a0192-0000-7000-8000-000000000002')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0192-0000-7000-8000-000000000021', '019a0192-0000-7000-8000-000000000001', 'tenant-admin', '租户管理员', true),
('019a0192-0000-7000-8000-000000000022', '019a0192-0000-7000-8000-000000000002', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id) VALUES
('019a0192-0000-7000-8000-000000000011', '019a0192-0000-7000-8000-000000000021'),
('019a0192-0000-7000-8000-000000000012', '019a0192-0000-7000-8000-000000000022')
ON CONFLICT DO NOTHING;

-- forge-platform 申报的既有权限；此测试不依赖外部服务启动。
INSERT INTO permission (id, code, service, name, realm)
VALUES ('019a0192-0000-7000-8000-000000000031', 'platform:tenant:manage', 'forge-platform', '管理租户', 'platform')
ON CONFLICT DO NOTHING;

-- 普通 Operator 只通过既有管理租户权限执行紧急清除；停用者即使有同一角色也不能操作。
INSERT INTO operator (id, account, display_name, password_hash, status)
SELECT CAST(v.id AS uuid), v.account, v.account, o.password_hash, v.status
FROM operator o CROSS JOIN (VALUES
    ('019a0192-0000-7000-8000-000000000041', 'op-ip-manager', 'active'),
    ('019a0192-0000-7000-8000-000000000042', 'op-ip-plain', 'active'),
    ('019a0192-0000-7000-8000-000000000043', 'op-ip-disabled', 'disabled')
) AS v (id, account, status)
WHERE o.account = 'operator' AND NOT o.deleted
ON CONFLICT DO NOTHING;
INSERT INTO platform_role (id, code, name)
VALUES ('019a0192-0000-7000-8000-000000000051', 'ip-tenant-manager', '管理租户')
ON CONFLICT DO NOTHING;
INSERT INTO platform_role_permission (role_id, permission_id)
VALUES ('019a0192-0000-7000-8000-000000000051', '019a0192-0000-7000-8000-000000000031')
ON CONFLICT DO NOTHING;
INSERT INTO operator_role (operator_id, role_id) VALUES
('019a0192-0000-7000-8000-000000000041', '019a0192-0000-7000-8000-000000000051'),
('019a0192-0000-7000-8000-000000000043', '019a0192-0000-7000-8000-000000000051')
ON CONFLICT DO NOTHING;

INSERT INTO tenant (id, name, deleted, ip_allowlist)
VALUES ('019a0192-0000-7000-8000-000000000003', '已删除紧急清除租户', true, '192.0.2.0/24')
ON CONFLICT DO NOTHING;
