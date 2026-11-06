-- 自定义角色的 Permission Code 授权（工单 16）。纯关联表，不带标准列；租户经 role.tenant_id 限定。
-- 预置角色不落授权记录，发布明细时取全部有效权限码。权限码下线后保留授权记录但不生效，再次上报恢复后随之恢复。
CREATE TABLE role_permission (
    role_id uuid NOT NULL REFERENCES role(id),
    permission_id uuid NOT NULL REFERENCES permission(id),
    PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE role_permission IS '自定义角色的 Permission Code 授权，预置角色不落记录';
COMMENT ON COLUMN role_permission.role_id IS '角色 ID';
COMMENT ON COLUMN role_permission.permission_id IS '权限 ID';
CREATE INDEX role_permission_permission_id_idx ON role_permission (permission_id);
