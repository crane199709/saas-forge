CREATE TABLE disposal_business (tenant_id uuid NOT NULL, value text NOT NULL);
ALTER TABLE disposal_business ENABLE ROW LEVEL SECURITY;
ALTER TABLE disposal_business FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON disposal_business
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
