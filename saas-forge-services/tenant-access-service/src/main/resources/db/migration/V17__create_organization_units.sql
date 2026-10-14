CREATE TABLE organizations (
    tenant_id UUID PRIMARY KEY REFERENCES tenants (id)
);

INSERT INTO organizations (tenant_id) SELECT id FROM tenants;

-- Tenant 建立时同步建立唯一 Organization，避免异步补建形成短暂无组织窗口。
CREATE FUNCTION create_tenant_organization() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $$
BEGIN
    INSERT INTO public.organizations (tenant_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER tenant_organization_created
    AFTER INSERT ON tenants FOR EACH ROW EXECUTE FUNCTION create_tenant_organization();

CREATE TABLE organization_units (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES organizations (tenant_id),
    parent_id UUID,
    name VARCHAR(200) NOT NULL,
    CONSTRAINT ck_organization_unit_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_organization_unit_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_organization_unit_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT uq_organization_unit_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_organization_unit_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES organization_units (tenant_id, id)
);
CREATE INDEX ix_organization_unit_parent ON organization_units (tenant_id, parent_id);

CREATE TABLE organization_unit_memberships (
    tenant_id UUID NOT NULL REFERENCES organizations (tenant_id),
    unit_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, unit_id, membership_id),
    CONSTRAINT fk_organization_unit_membership_unit FOREIGN KEY (tenant_id, unit_id)
        REFERENCES organization_units (tenant_id, id),
    CONSTRAINT fk_organization_unit_membership_member FOREIGN KEY (tenant_id, membership_id)
        REFERENCES memberships (tenant_id, id)
);
CREATE INDEX ix_organization_unit_membership_member
    ON organization_unit_memberships (tenant_id, membership_id);

ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
ALTER TABLE organization_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_units FORCE ROW LEVEL SECURITY;
ALTER TABLE organization_unit_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_unit_memberships FORCE ROW LEVEL SECURITY;

CREATE POLICY organizations_app ON organizations FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY organizations_migrator ON organizations FOR ALL TO tenant_access_migrator
    USING (true) WITH CHECK (true);
CREATE POLICY organization_units_app ON organization_units FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY organization_units_migrator ON organization_units FOR ALL TO tenant_access_migrator
    USING (true) WITH CHECK (true);
CREATE POLICY organization_unit_memberships_app ON organization_unit_memberships FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY organization_unit_memberships_migrator ON organization_unit_memberships FOR ALL TO tenant_access_migrator
    USING (true) WITH CHECK (true);

GRANT SELECT, INSERT ON organizations TO tenant_access_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON organization_units TO tenant_access_app;
GRANT SELECT, INSERT, DELETE ON organization_unit_memberships TO tenant_access_app;

-- 管理 Permission 的固定语义随此制品交付，不向 Console 暴露目录写入能力。
INSERT INTO permission_catalog
    (code, semantic_id, source, enabled, first_revision, last_revision, updated_at)
VALUES ('system:organization:manage', 'manageOrganization', 'tenant-access-service', true, 1, 1, statement_timestamp());
