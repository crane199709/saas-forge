-- 全部 Example 写操作共用调用方键空间；tenant_id 不能进入唯一键并放宽调用方唯一语义。
CREATE TABLE project_write_results (
    identity_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    tenant_id uuid NOT NULL,
    fingerprint text NOT NULL,
    status integer,
    body text,
    location text,
    completed_at timestamptz,
    PRIMARY KEY (identity_id, idempotency_key),
    CHECK ((status IS NULL AND body IS NULL AND completed_at IS NULL)
        OR (status BETWEEN 200 AND 499 AND status <> 400 AND body IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX project_write_results_tenant_expiry ON project_write_results (tenant_id, completed_at);
ALTER TABLE project_write_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_write_results FORCE ROW LEVEL SECURITY;
CREATE POLICY project_write_results_tenant ON project_write_results TO project_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY project_write_results_maintenance ON project_write_results TO project_migrator
    USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON project_write_results TO project_app;
