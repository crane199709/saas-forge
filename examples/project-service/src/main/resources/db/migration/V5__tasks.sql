CREATE TABLE tasks (
    id uuid CONSTRAINT pk_tasks PRIMARY KEY DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    title varchar(200) NOT NULL CONSTRAINT ck_tasks_title CHECK (length(btrim(title)) > 0),
    description varchar(2000),
    status varchar(20) NOT NULL DEFAULT 'TODO' CONSTRAINT ck_tasks_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    version bigint NOT NULL DEFAULT 1 CONSTRAINT ck_tasks_version CHECK (version > 0),
    created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    updated_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    CONSTRAINT fk_tasks_tenant_project FOREIGN KEY (tenant_id, project_id)
        REFERENCES projects (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX idx_tasks_tenant_project_id ON tasks (tenant_id, project_id, id);
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tasks_tenant ON tasks TO project_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tasks_maintenance ON tasks TO project_migrator USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON tasks TO project_app;
-- 归属创建后不可变；运行账号只拥有本切片必需的 SELECT/INSERT，没有 UPDATE/DELETE。
