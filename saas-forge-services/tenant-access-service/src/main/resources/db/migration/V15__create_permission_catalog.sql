-- Permission definitions are release-owned. Runtime connections can only read them.
CREATE TABLE permission_catalog (
    code VARCHAR(160) PRIMARY KEY,
    semantic_id VARCHAR(160) NOT NULL,
    source VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL,
    first_revision INTEGER NOT NULL,
    last_revision INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_permission_code CHECK (code ~ '^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$'),
    CONSTRAINT ck_permission_source CHECK (source ~ '^[a-z][a-z0-9-]*$'),
    CONSTRAINT ck_permission_semantic CHECK (semantic_id ~ '^[a-zA-Z][a-zA-Z0-9]*$'),
    CONSTRAINT ck_permission_revision CHECK (first_revision > 0 AND last_revision >= first_revision)
);

CREATE TABLE permission_catalog_releases (
    source VARCHAR(80) NOT NULL,
    revision INTEGER NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, revision),
    CONSTRAINT ck_permission_release_revision CHECK (revision > 0),
    CONSTRAINT ck_permission_release_sha CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE permission_catalog_changes (
    source VARCHAR(80) NOT NULL,
    revision INTEGER NOT NULL,
    code VARCHAR(160) NOT NULL REFERENCES permission_catalog (code),
    enabled BOOLEAN NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, revision, code),
    FOREIGN KEY (source, revision) REFERENCES permission_catalog_releases (source, revision)
);

CREATE TABLE tenant_role_permissions (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    role_id UUID NOT NULL,
    permission_code VARCHAR(160) NOT NULL REFERENCES permission_catalog (code),
    assigned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES tenant_roles (tenant_id, id)
);

ALTER TABLE tenant_role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_role_permissions_runtime_access ON tenant_role_permissions
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_role_permissions_migration_access ON tenant_role_permissions
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

REVOKE ALL ON permission_catalog, permission_catalog_releases, permission_catalog_changes FROM PUBLIC;
GRANT SELECT ON permission_catalog TO tenant_access_app;
GRANT SELECT, INSERT, DELETE ON tenant_role_permissions TO tenant_access_app;

-- This function is available to the controlled migrator only. An omitted code keeps its prior status.
CREATE FUNCTION import_permission_catalog(p_manifest JSONB, p_artifact_sha256 TEXT, p_topic TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_source TEXT := p_manifest->>'source';
    v_revision INTEGER;
    v_entry JSONB;
    v_code TEXT;
    v_semantic TEXT;
    v_enabled BOOLEAN;
    v_current public.permission_catalog%ROWTYPE;
    v_changed INTEGER := 0;
    v_tenant UUID;
    v_event UUID;
    v_time TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF jsonb_typeof(p_manifest) <> 'object'
        OR jsonb_typeof(p_manifest->'revision') <> 'number'
        OR (p_manifest->>'revision') !~ '^[1-9][0-9]*$'
        OR v_source !~ '^[a-z][a-z0-9-]*$'
        OR jsonb_typeof(p_manifest->'permissions') <> 'array'
        OR jsonb_array_length(p_manifest->'permissions') = 0
        OR p_artifact_sha256 !~ '^[0-9a-f]{64}$'
        OR p_topic !~ '^saas\.forge\.[a-z][a-z0-9-]*\.tenant-access-service\.events$'
    THEN
        RAISE EXCEPTION 'invalid permission manifest';
    END IF;
    v_revision := (p_manifest->>'revision')::INTEGER;
    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(p_manifest->'permissions') entry
        WHERE jsonb_typeof(entry) <> 'object'
            OR entry->>'code' !~ '^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$'
            OR split_part(entry->>'code', ':', 1) <> split_part(v_source, '-service', 1)
            OR entry->>'semanticId' !~ '^[a-zA-Z][a-zA-Z0-9]*$'
            OR jsonb_typeof(entry->'enabled') <> 'boolean'
            OR (SELECT count(*) FROM jsonb_object_keys(entry)) <> 3
    ) OR (SELECT count(*) FROM jsonb_array_elements(p_manifest->'permissions')) <> (
        SELECT count(DISTINCT entry->>'code') FROM jsonb_array_elements(p_manifest->'permissions') entry
    ) THEN
        RAISE EXCEPTION 'invalid permission entries';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext(v_source));
    IF EXISTS (SELECT 1 FROM public.permission_catalog_releases
               WHERE source = v_source AND revision = v_revision AND artifact_sha256 = p_artifact_sha256) THEN
        RETURN 0;
    END IF;
    IF EXISTS (SELECT 1 FROM public.permission_catalog_releases
               WHERE source = v_source AND revision >= v_revision) THEN
        RAISE EXCEPTION 'permission revision is stale or reused';
    END IF;
    INSERT INTO public.permission_catalog_releases (source, revision, artifact_sha256, imported_at)
    VALUES (v_source, v_revision, p_artifact_sha256, v_time);

    FOR v_entry IN SELECT value FROM jsonb_array_elements(p_manifest->'permissions') LOOP
        v_code := v_entry->>'code';
        v_semantic := v_entry->>'semanticId';
        v_enabled := (v_entry->>'enabled')::BOOLEAN;
        SELECT * INTO v_current FROM public.permission_catalog WHERE code = v_code FOR UPDATE;
        IF FOUND AND (v_current.semantic_id <> v_semantic OR v_current.source <> v_source) THEN
            RAISE EXCEPTION 'permission semantic reuse forbidden: %', v_code;
        END IF;
        IF NOT FOUND THEN
            INSERT INTO public.permission_catalog
                (code, semantic_id, source, enabled, first_revision, last_revision, updated_at)
            VALUES (v_code, v_semantic, v_source, v_enabled, v_revision, v_revision, v_time);
        ELSE
            UPDATE public.permission_catalog SET enabled = v_enabled,
                last_revision = v_revision, updated_at = v_time WHERE code = v_code;
        END IF;
        IF NOT FOUND OR v_current.enabled IS DISTINCT FROM v_enabled THEN
            v_changed := v_changed + 1;
            INSERT INTO public.permission_catalog_changes (source, revision, code, enabled, changed_at)
            VALUES (v_source, v_revision, v_code, v_enabled, v_time);
            FOR v_tenant IN SELECT id FROM public.tenants LOOP
                v_event := uuidv7();
                INSERT INTO public.tenant_access_outbox_events
                    (event_id, tenant_id, occurred_at, topic, ordering_key, event_snapshot, next_attempt_at)
                VALUES (v_event, v_tenant, v_time, p_topic, v_tenant::TEXT,
                    jsonb_build_object(
                        'specversion', '1.0', 'id', v_event::TEXT,
                        'source', 'urn:saas.forge:tenant-access-service',
                        'type', 'com.saas.forge.permission.catalog-changed.v1',
                        'subject', v_tenant::TEXT,
                        'time', to_char(v_time AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                        'datacontenttype', 'application/json',
                        'dataschema', 'https://saas.forge.io/contracts/events/permission-catalog-changed.v1.schema.json',
                        'data', jsonb_build_object('tenantId', v_tenant::TEXT, 'code', v_code,
                            'enabled', v_enabled, 'source', v_source, 'revision', v_revision)),
                    v_time);
            END LOOP;
        END IF;
    END LOOP;
    RETURN v_changed;
END;
$$;
REVOKE ALL ON FUNCTION import_permission_catalog(JSONB, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION import_permission_catalog(JSONB, TEXT, TEXT) TO tenant_access_migrator;
