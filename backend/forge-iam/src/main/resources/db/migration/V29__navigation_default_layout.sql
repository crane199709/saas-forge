-- 默认布局（导航布局规格第 9 节）：两端没有自己的布局时生效的初始分组。没有修改界面，修改靠新的迁移。
CREATE TABLE navigation_default_layout (
    id uuid PRIMARY KEY,
    realm varchar(16) NOT NULL CHECK (realm IN ('tenant', 'platform')),
    layout jsonb NOT NULL CHECK (jsonb_typeof(layout) = 'object'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX navigation_default_layout_realm_key ON navigation_default_layout (realm) WHERE NOT deleted;
COMMENT ON TABLE navigation_default_layout IS '导航默认布局（Navigation Layout），每个 Realm 最多一条未删除记录';
COMMENT ON COLUMN navigation_default_layout.id IS '默认布局 ID，UUIDv7';
COMMENT ON COLUMN navigation_default_layout.realm IS '布局所属 Realm：tenant 为租户端，platform 为平台控制台';
COMMENT ON COLUMN navigation_default_layout.layout IS '布局内容，节点树 JSON，格式同租户与平台自己的布局';
COMMENT ON COLUMN navigation_default_layout.created_at IS '创建时间';
COMMENT ON COLUMN navigation_default_layout.updated_at IS '最后更新时间';
COMMENT ON COLUMN navigation_default_layout.created_by IS '创建人，迁移写入为 NULL';
COMMENT ON COLUMN navigation_default_layout.updated_by IS '最后更新人，迁移写入为 NULL';
COMMENT ON COLUMN navigation_default_layout.deleted IS '逻辑删除标记';

-- 初始分组是固定 id 的自定义分组，可以改名和删除。首页与通知中心固定在导航顶部，不进入布局；
-- 清单菜单不在默认布局中，按合并规则放在顶层末尾。
INSERT INTO navigation_default_layout (id, realm, layout) VALUES
('019a6e00-0000-7000-8000-000000000001', 'tenant', CAST('{"nodes": [
  {"page": "announcements"},
  {"group": "org-access", "name": "组织与权限", "children": [
    {"page": "members"}, {"page": "org"}, {"page": "roles"}]},
  {"group": "security-audit", "name": "安全与审计", "children": [
    {"page": "security-settings"}, {"page": "audit"}]},
  {"group": "system-settings", "name": "系统设置", "children": [
    {"page": "dictionaries"}, {"page": "parameters"}, {"page": "menu-layout"}]}
]}' AS jsonb)),
('019a6e00-0000-7000-8000-000000000002', 'platform', CAST('{"nodes": [
  {"group": "tenant-operations", "name": "租户运营", "children": [
    {"page": "tenants"}, {"page": "plans"}, {"page": "sub-apps"}, {"page": "announcements"}]},
  {"group": "access-control", "name": "访问控制", "children": [
    {"page": "operators"}, {"page": "identities"}, {"page": "roles"}]},
  {"group": "operations", "name": "运维", "children": [
    {"page": "audit"}, {"page": "task-runs"}, {"page": "dead-events"}]},
  {"page": "menu-layout"}
]}' AS jsonb));
