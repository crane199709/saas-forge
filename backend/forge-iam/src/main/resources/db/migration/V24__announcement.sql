-- 公告为标准业务表；租户内容和已读状态通过显式条件与 RLS 双重隔离。
CREATE TABLE tenant_announcement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    title varchar(200) NOT NULL,
    body text NOT NULL,
    sub_app varchar(64),
    route varchar(2048),
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked boolean NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE tenant_announcement IS '租户公告';
COMMENT ON COLUMN tenant_announcement.id IS '公告标识，UUIDv7';
COMMENT ON COLUMN tenant_announcement.tenant_id IS '所属租户标识';
COMMENT ON COLUMN tenant_announcement.title IS '纯文本标题';
COMMENT ON COLUMN tenant_announcement.body IS '纯文本正文';
COMMENT ON COLUMN tenant_announcement.sub_app IS '跳转的子应用编码';
COMMENT ON COLUMN tenant_announcement.route IS '子应用内的路由';
COMMENT ON COLUMN tenant_announcement.expires_at IS '到期时间，空表示长期有效';
COMMENT ON COLUMN tenant_announcement.revoked IS '是否已撤回';
COMMENT ON COLUMN tenant_announcement.created_at IS '创建时间';
COMMENT ON COLUMN tenant_announcement.updated_at IS '最后更新时间';
COMMENT ON COLUMN tenant_announcement.created_by IS '创建人标识，系统操作为空';
COMMENT ON COLUMN tenant_announcement.updated_by IS '最后更新人标识，系统操作为空';
COMMENT ON COLUMN tenant_announcement.deleted IS '逻辑删除标记';
ALTER TABLE tenant_announcement ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_announcement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_announcement_tenant ON tenant_announcement
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
CREATE TABLE announcement_read (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    membership_id uuid NOT NULL REFERENCES membership(id),
    announcement_id uuid NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE announcement_read IS '按租户成员关系记录的公告已读状态';
COMMENT ON COLUMN announcement_read.id IS '已读记录标识，UUIDv7';
COMMENT ON COLUMN announcement_read.tenant_id IS '所属租户标识';
COMMENT ON COLUMN announcement_read.membership_id IS '已读的成员关系标识';
COMMENT ON COLUMN announcement_read.announcement_id IS '公告标识，可以来自租户或平台公告';
COMMENT ON COLUMN announcement_read.created_at IS '创建时间';
COMMENT ON COLUMN announcement_read.updated_at IS '最后更新时间';
COMMENT ON COLUMN announcement_read.created_by IS '创建人标识，系统操作为空';
COMMENT ON COLUMN announcement_read.updated_by IS '最后更新人标识，系统操作为空';
COMMENT ON COLUMN announcement_read.deleted IS '逻辑删除标记';
ALTER TABLE announcement_read ENABLE ROW LEVEL SECURITY;
ALTER TABLE announcement_read FORCE ROW LEVEL SECURITY;
CREATE POLICY announcement_read_tenant ON announcement_read
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
CREATE UNIQUE INDEX announcement_read_member_key ON announcement_read (tenant_id, membership_id, announcement_id) WHERE NOT deleted;

-- 平台公告和投放目标是平台全局管理数据，不从当前租户展开内容副本。
CREATE TABLE platform_announcement (
    id uuid PRIMARY KEY,
    title varchar(200) NOT NULL,
    body text NOT NULL,
    sub_app varchar(64),
    route varchar(2048),
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked boolean NOT NULL DEFAULT false,
    all_tenants boolean NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE platform_announcement IS '平台发布的全租户或指定租户公告';
COMMENT ON COLUMN platform_announcement.id IS '公告标识，UUIDv7';
COMMENT ON COLUMN platform_announcement.title IS '纯文本标题';
COMMENT ON COLUMN platform_announcement.body IS '纯文本正文';
COMMENT ON COLUMN platform_announcement.sub_app IS '跳转的子应用编码';
COMMENT ON COLUMN platform_announcement.route IS '子应用内的路由';
COMMENT ON COLUMN platform_announcement.expires_at IS '到期时间，空表示长期有效';
COMMENT ON COLUMN platform_announcement.revoked IS '是否已撤回';
COMMENT ON COLUMN platform_announcement.all_tenants IS '是否向全部租户包括生效期内新租户投放';
COMMENT ON COLUMN platform_announcement.created_at IS '创建时间';
COMMENT ON COLUMN platform_announcement.updated_at IS '最后更新时间';
COMMENT ON COLUMN platform_announcement.created_by IS '创建运营账号标识';
COMMENT ON COLUMN platform_announcement.updated_by IS '最后更新运营账号标识';
COMMENT ON COLUMN platform_announcement.deleted IS '逻辑删除标记';
CREATE TABLE platform_announcement_target (
    announcement_id uuid NOT NULL REFERENCES platform_announcement(id),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    PRIMARY KEY (announcement_id, tenant_id)
);
COMMENT ON TABLE platform_announcement_target IS '平台公告的指定投放租户关联，全部租户公告不写入';
COMMENT ON COLUMN platform_announcement_target.announcement_id IS '平台公告标识';
COMMENT ON COLUMN platform_announcement_target.tenant_id IS '目标租户标识';
