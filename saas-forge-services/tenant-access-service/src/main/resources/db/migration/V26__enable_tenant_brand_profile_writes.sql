ALTER TABLE tenant_brand_profiles
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision >= 1);

GRANT INSERT, UPDATE ON tenant_brand_profiles TO tenant_access_app;
