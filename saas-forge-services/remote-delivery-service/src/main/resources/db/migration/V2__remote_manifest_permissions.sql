ALTER TABLE remote_manifests
    ADD COLUMN permissions JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE remote_manifests
    ADD CONSTRAINT ck_remote_manifest_permissions
    CHECK (jsonb_typeof(permissions) = 'array' AND jsonb_array_length(permissions) <= 10);
