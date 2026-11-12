CREATE TABLE service_credential (
    id uuid PRIMARY KEY,
    service varchar(100) NOT NULL,
    digest varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('active', 'disabled')),
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX service_credential_digest_key ON service_credential (digest) WHERE NOT deleted;
CREATE INDEX service_credential_service_idx ON service_credential (service) WHERE NOT deleted;
COMMENT ON TABLE service_credential IS '服务身份认证凭证，仅保存摘要';
COMMENT ON COLUMN service_credential.id IS '凭证 ID，UUIDv7';
COMMENT ON COLUMN service_credential.service IS '凭证绑定的服务名称';
COMMENT ON COLUMN service_credential.digest IS '凭证的 SHA-256 摘要';
COMMENT ON COLUMN service_credential.status IS '凭证状态：active 有效，disabled 已停用';
COMMENT ON COLUMN service_credential.last_used_at IS '最近成功认证时间';
COMMENT ON COLUMN service_credential.created_at IS '创建时间';
COMMENT ON COLUMN service_credential.updated_at IS '最近修改时间';
COMMENT ON COLUMN service_credential.created_by IS '创建凭证的运营人员 ID';
COMMENT ON COLUMN service_credential.updated_by IS '最近修改凭证的运营人员 ID';
COMMENT ON COLUMN service_credential.deleted IS '逻辑删除标记';
