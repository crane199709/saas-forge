-- forge-sdk-storage 需要的表。服务把本文件内容复制到自己的 Flyway 迁移中执行，理由同 forge-sdk-mq 的 schema.sql。
-- 两张表都按租户启用 RLS：读写都在租户上下文中进行，SDK 的 SQL 另经租户拦截注入 tenant_id 条件。
-- 属于 SDK 基础设施表，不带标准列：对象登记随 S3 对象一起存在，不做逻辑删除。

-- 本服务登记的对象。PENDING 是已签发上传 URL、尚未确认上传的对象，在 upload_expires_at 之前占用存储容量，
-- 过期后不再计入；STORED 是已确认上传的对象。
CREATE TABLE forge_storage_object (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    object_key varchar(512) NOT NULL,
    purpose varchar(64) NOT NULL,
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
COMMENT ON COLUMN forge_storage_object.purpose IS '存储用途编码，unspecified 表示尚未归类的存量对象';
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
