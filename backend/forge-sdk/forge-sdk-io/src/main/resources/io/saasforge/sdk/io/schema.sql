CREATE TABLE forge_io_task (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    type varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    membership_id uuid NOT NULL,
    query_summary text,
    row_count bigint NOT NULL DEFAULT 0,
    error_code varchar(100),
    failure_reason varchar(1000),
    source_object_id uuid,
    result_object_id uuid,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE forge_io_task IS '业务服务的异步导入导出任务';
COMMENT ON COLUMN forge_io_task.id IS '任务 ID，UUIDv7';
COMMENT ON COLUMN forge_io_task.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN forge_io_task.type IS '类型 IMPORT 或 EXPORT';
COMMENT ON COLUMN forge_io_task.status IS '状态 WAITING、RUNNING、SUCCEEDED、FAILED';
COMMENT ON COLUMN forge_io_task.query_summary IS '查询条件摘要，不含凭证或敏感信息';
COMMENT ON COLUMN forge_io_task.membership_id IS '提交人 Membership ID';
COMMENT ON COLUMN forge_io_task.row_count IS '已处理的数据行数';
COMMENT ON COLUMN forge_io_task.error_code IS '失败错误码';
COMMENT ON COLUMN forge_io_task.failure_reason IS '可展示的失败原因';
COMMENT ON COLUMN forge_io_task.source_object_id IS '导入源文件对象 ID';
COMMENT ON COLUMN forge_io_task.result_object_id IS '导出或错误报告对象 ID';
COMMENT ON COLUMN forge_io_task.heartbeat_at IS '最后心跳时间';
COMMENT ON COLUMN forge_io_task.finished_at IS '任务结束时间';
COMMENT ON COLUMN forge_io_task.created_at IS '创建时间';
COMMENT ON COLUMN forge_io_task.updated_at IS '最后修改时间';
COMMENT ON COLUMN forge_io_task.created_by IS '创建操作人 Identity ID';
COMMENT ON COLUMN forge_io_task.updated_by IS '最后修改操作人 Identity ID';
COMMENT ON COLUMN forge_io_task.deleted IS '逻辑删除标记';

CREATE INDEX forge_io_task_owner_idx ON forge_io_task (tenant_id,membership_id,created_at);
ALTER TABLE forge_io_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_io_task FORCE ROW LEVEL SECURITY;
CREATE POLICY forge_io_task_tenant_isolation ON forge_io_task
USING (tenant_id = CAST(nullif(current_setting('app.tenant_id',true),'') AS uuid))
WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id',true),'') AS uuid));

ALTER TABLE forge_io_task ADD COLUMN import_model varchar(500);
COMMENT ON COLUMN forge_io_task.import_model IS '导入模型全限定类名，区分确认路由与同类记录，历史任务为空';
ALTER TABLE forge_io_task ADD COLUMN snapshot_count bigint NOT NULL DEFAULT 0;
ALTER TABLE forge_io_task ADD COLUMN snapshot_expires_at TIMESTAMP WITH TIME ZONE;
COMMENT ON COLUMN forge_io_task.snapshot_count IS '该次导入保存的覆盖前记录快照数，不含新增行';
COMMENT ON COLUMN forge_io_task.snapshot_expires_at IS '覆盖前快照的实际保留截止时间';

CREATE TABLE forge_import_backup (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    task_id uuid NOT NULL,
    record_id varchar(500) NOT NULL,
    field_values jsonb NOT NULL,
    imported_version text,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE forge_import_backup IS '覆盖导入前的字段旧值快照，不包含本次新增记录';
COMMENT ON COLUMN forge_import_backup.id IS '快照 ID，UUIDv7';
COMMENT ON COLUMN forge_import_backup.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN forge_import_backup.task_id IS '所属导入任务 ID';
COMMENT ON COLUMN forge_import_backup.record_id IS '业务记录主键，由业务写入器提供';
COMMENT ON COLUMN forge_import_backup.field_values IS '本次导入会覆盖的字段及其覆盖前取值，允许字段原值为空';
COMMENT ON COLUMN forge_import_backup.imported_version IS '本次导入最后一次覆盖后的记录修改标记';
COMMENT ON COLUMN forge_import_backup.expires_at IS '快照保留截止时间';
COMMENT ON COLUMN forge_import_backup.created_at IS '创建时间';
COMMENT ON COLUMN forge_import_backup.updated_at IS '最后修改时间';
COMMENT ON COLUMN forge_import_backup.created_by IS '创建操作人 Identity ID，后台系统写入为空';
COMMENT ON COLUMN forge_import_backup.updated_by IS '最后修改操作人 Identity ID，后台系统写入为空';
COMMENT ON COLUMN forge_import_backup.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX forge_import_backup_record_key ON forge_import_backup (tenant_id,task_id,record_id) WHERE NOT deleted;
CREATE INDEX forge_import_backup_expiry_idx ON forge_import_backup (tenant_id,expires_at) WHERE NOT deleted;
ALTER TABLE forge_import_backup ENABLE ROW LEVEL SECURITY;
ALTER TABLE forge_import_backup FORCE ROW LEVEL SECURITY;
CREATE POLICY forge_import_backup_tenant_isolation ON forge_import_backup
USING (tenant_id = CAST(nullif(current_setting('app.tenant_id',true),'') AS uuid))
WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id',true),'') AS uuid));
