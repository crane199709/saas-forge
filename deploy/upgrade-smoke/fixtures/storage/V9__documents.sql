CREATE TABLE document_files (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    object_id uuid NOT NULL REFERENCES forge_storage_object(id),
    title varchar(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE document_files IS '业务文档及其存储对象引用';
COMMENT ON COLUMN document_files.id IS '文档 ID';
COMMENT ON COLUMN document_files.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN document_files.object_id IS '已确认的存储对象 ID';
COMMENT ON COLUMN document_files.title IS '业务文档标题';
COMMENT ON COLUMN document_files.created_at IS '创建时间';
COMMENT ON COLUMN document_files.updated_at IS '更新时间';
COMMENT ON COLUMN document_files.created_by IS '创建成员 ID';
COMMENT ON COLUMN document_files.updated_by IS '更新成员 ID';
COMMENT ON COLUMN document_files.deleted IS '逻辑删除标记';
ALTER TABLE document_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_files FORCE ROW LEVEL SECURITY;
CREATE POLICY document_files_tenant_isolation ON document_files
USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
