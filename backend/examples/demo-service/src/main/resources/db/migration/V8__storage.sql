-- 对象存储（工单 24）：forge-sdk-storage 需要的表，与 SDK 中 io/saasforge/sdk/storage/schema.sql 一致；按租户启用 RLS。
CREATE TABLE forge_storage_object (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    object_key varchar(512) NOT NULL,
    filename varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    size bigint NOT NULL,
    status varchar(16) NOT NULL,
    upload_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    stored_at timestamptz
);
CREATE INDEX forge_storage_object_tenant_id_idx ON forge_storage_object (tenant_id);

ALTER TABLE forge_storage_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_storage_object FORCE ROW LEVEL SECURITY;

CREATE POLICY forge_storage_object_tenant_isolation ON forge_storage_object
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 租户的存储容量上限，由 TenantPlanChanged 写入；quota_bytes 为 NULL 表示不限，没有记录的租户同样不限。
-- 上传前锁定本行，同一租户的并发上传依次比较已用容量。
CREATE TABLE forge_storage_quota (
    tenant_id uuid PRIMARY KEY,
    quota_bytes bigint,
    plan_version bigint NOT NULL
);

ALTER TABLE forge_storage_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_storage_quota FORCE ROW LEVEL SECURITY;

CREATE POLICY forge_storage_quota_tenant_isolation ON forge_storage_quota
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 订单附件：文件内容在对象存储中，这里只记录归属的订单与 SDK 返回的对象 ID。一个对象只能作为一个附件。
CREATE TABLE order_attachments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    object_id uuid NOT NULL,
    filename varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    size bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX order_attachments_order_id_idx ON order_attachments (tenant_id, order_id);
CREATE UNIQUE INDEX order_attachments_object_id_key ON order_attachments (object_id) WHERE NOT deleted;

ALTER TABLE order_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_attachments FORCE ROW LEVEL SECURITY;

CREATE POLICY order_attachments_tenant_isolation ON order_attachments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
