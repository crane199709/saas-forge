-- 对象存储（工单 24）：forge-sdk-storage 需要的表，与 SDK 中 io/saasforge/sdk/storage/schema.sql 一致；按租户启用 RLS。
CREATE TABLE forge_storage_object (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    object_key varchar(512) NOT NULL,
    filename varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    size bigint NOT NULL,
    status varchar(16) NOT NULL,
    upload_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stored_at TIMESTAMP WITH TIME ZONE
);
COMMENT ON TABLE forge_storage_object IS '对象存储登记：记录本服务租户的对象及其上传状态';
COMMENT ON COLUMN forge_storage_object.id IS '对象 ID，UUIDv7';
COMMENT ON COLUMN forge_storage_object.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN forge_storage_object.object_key IS '对象存储中的 Key';
COMMENT ON COLUMN forge_storage_object.filename IS '原始文件名';
COMMENT ON COLUMN forge_storage_object.content_type IS '内容类型';
COMMENT ON COLUMN forge_storage_object.size IS '对象大小，单位字节';
COMMENT ON COLUMN forge_storage_object.status IS '状态：PENDING 已签发上传地址未确认，STORED 已确认上传';
COMMENT ON COLUMN forge_storage_object.upload_expires_at IS '上传地址过期时间，PENDING 对象在此之前占用存储容量';
COMMENT ON COLUMN forge_storage_object.created_at IS '登记时间';
COMMENT ON COLUMN forge_storage_object.stored_at IS '确认上传时间，未确认时为 NULL';
CREATE INDEX forge_storage_object_tenant_id_idx ON forge_storage_object (tenant_id);

ALTER TABLE forge_storage_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_storage_object FORCE ROW LEVEL SECURITY;

CREATE POLICY forge_storage_object_tenant_isolation ON forge_storage_object
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));

-- 租户的存储容量上限，由 TenantPlanChanged 写入；quota_bytes 为 NULL 表示不限，没有记录的租户同样不限。
-- 上传前锁定本行，同一租户的并发上传依次比较已用容量。
CREATE TABLE forge_storage_quota (
    tenant_id uuid PRIMARY KEY,
    quota_bytes bigint,
    plan_version bigint NOT NULL
);
COMMENT ON TABLE forge_storage_quota IS '租户存储容量上限：由 TenantPlanChanged 写入，没有记录的租户不限';
COMMENT ON COLUMN forge_storage_quota.tenant_id IS '租户 ID';
COMMENT ON COLUMN forge_storage_quota.quota_bytes IS '容量上限，单位字节，NULL 表示不限';
COMMENT ON COLUMN forge_storage_quota.plan_version IS '已应用的 Plan 版本，用于丢弃乱序送达的旧变更';

ALTER TABLE forge_storage_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_storage_quota FORCE ROW LEVEL SECURITY;

CREATE POLICY forge_storage_quota_tenant_isolation ON forge_storage_quota
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));

-- 订单附件：文件内容在对象存储中，这里只记录归属的订单与 SDK 返回的对象 ID。一个对象只能作为一个附件。
CREATE TABLE order_attachments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    object_id uuid NOT NULL,
    filename varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    size bigint NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE order_attachments IS '示例订单附件：文件内容在对象存储中，一个对象只能作为一个附件';
COMMENT ON COLUMN order_attachments.id IS '附件 ID，UUIDv7';
COMMENT ON COLUMN order_attachments.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN order_attachments.order_id IS '关联的订单 ID';
COMMENT ON COLUMN order_attachments.object_id IS 'SDK 返回的对象 ID';
COMMENT ON COLUMN order_attachments.filename IS '文件名';
COMMENT ON COLUMN order_attachments.content_type IS '内容类型';
COMMENT ON COLUMN order_attachments.size IS '文件大小，单位字节';
COMMENT ON COLUMN order_attachments.created_at IS '创建时间';
COMMENT ON COLUMN order_attachments.updated_at IS '最后更新时间';
COMMENT ON COLUMN order_attachments.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_attachments.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_attachments.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE INDEX order_attachments_order_id_idx ON order_attachments (tenant_id, order_id);
CREATE UNIQUE INDEX order_attachments_object_id_key ON order_attachments (object_id) WHERE NOT deleted;

ALTER TABLE order_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_attachments FORCE ROW LEVEL SECURITY;

CREATE POLICY order_attachments_tenant_isolation ON order_attachments
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
