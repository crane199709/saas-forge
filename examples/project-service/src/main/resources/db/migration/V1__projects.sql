CREATE TABLE projects (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    description varchar(2000),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    updated_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    UNIQUE (tenant_id, id)
);
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
CREATE POLICY projects_tenant ON projects TO project_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY projects_maintenance ON projects TO project_migrator USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON projects TO project_app;
