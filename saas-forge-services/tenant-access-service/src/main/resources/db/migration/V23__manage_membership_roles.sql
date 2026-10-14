-- 撤销角色绑定由 Tenant Access 应用角色执行。
GRANT DELETE ON membership_role_assignments TO tenant_access_app;

-- Invitation 管理已有执行入口，可通过自定义 Tenant Role 委派。
INSERT INTO permission_catalog
    (code, semantic_id, source, enabled, first_revision, last_revision, updated_at)
VALUES ('tenant-access:invitation:manage', 'manageInvitations', 'tenant-access-service', true, 1, 1, statement_timestamp());
