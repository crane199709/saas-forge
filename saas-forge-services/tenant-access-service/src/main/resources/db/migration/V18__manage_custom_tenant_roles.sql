-- 自定义角色由 Tenant Access 应用管理；Permission 目录仍只有发布角色可写。
ALTER TABLE tenant_roles ADD CONSTRAINT ck_custom_role_key
    CHECK (role_key ~ '^[A-Z][A-Z0-9_]{2,63}$');
GRANT UPDATE, DELETE ON tenant_roles TO tenant_access_app;

-- 已有角色外键阻止跨 Tenant 绑定；变更时还须在服务端锁定角色。
