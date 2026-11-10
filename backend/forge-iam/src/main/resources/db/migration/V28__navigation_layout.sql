-- Navigation Layout（导航布局规格第 6 节）：只决定导航中节点的位置，不决定可见性。两端各自保存，没有继承关系。

-- 租户的布局随租户行保存，NULL 表示使用默认导航。
ALTER TABLE tenant ADD COLUMN navigation_layout jsonb CHECK (jsonb_typeof(navigation_layout) = 'object');
COMMENT ON COLUMN tenant.navigation_layout IS '租户导航布局（Navigation Layout），NULL 表示使用默认导航';

-- 平台控制台的布局。最多一条未删除记录，由保存时的事务级咨询锁维持；恢复默认即逻辑删除。
CREATE TABLE platform_navigation_layout (
    id uuid PRIMARY KEY,
    layout jsonb NOT NULL CHECK (jsonb_typeof(layout) = 'object'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE platform_navigation_layout IS '平台控制台导航布局（Navigation Layout），最多一条未删除记录';
COMMENT ON COLUMN platform_navigation_layout.id IS '布局 ID，UUIDv7';
COMMENT ON COLUMN platform_navigation_layout.layout IS '布局内容，节点树 JSON';
COMMENT ON COLUMN platform_navigation_layout.created_at IS '创建时间';
COMMENT ON COLUMN platform_navigation_layout.updated_at IS '最后更新时间';
COMMENT ON COLUMN platform_navigation_layout.created_by IS '创建人（Operator），系统操作为 NULL';
COMMENT ON COLUMN platform_navigation_layout.updated_by IS '最后更新人（Operator），系统操作为 NULL';
COMMENT ON COLUMN platform_navigation_layout.deleted IS '逻辑删除标记，恢复默认时置为 true';
