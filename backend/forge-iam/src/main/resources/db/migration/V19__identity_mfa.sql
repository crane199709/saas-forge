-- 账号安全工单 01：Identity 的 Authenticator 与 Recovery Code。Operator 的另建表（ADR-0003），Authenticator 属于全局 Identity，
-- 在其全部 Tenant 通用（ADR-0019），因此没有 tenant_id 与 RLS。

-- 每个 Identity 至多一个未删除的 Authenticator。密钥以 AES-GCM 加密存放（加密密钥见 forge.iam.mfa.encryption-key）；
-- last_used_step 是上次通过校验的 TOTP 时间步，只接受更晚的步长，同一动态码因此只能用一次。
CREATE TABLE identity_authenticator (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES identity(id),
    secret_ciphertext bytea NOT NULL,
    last_used_step bigint NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE identity_authenticator IS 'Identity 的 Authenticator：每个 Identity 至多一个未删除的记录';
COMMENT ON COLUMN identity_authenticator.id IS 'Authenticator ID，UUIDv7';
COMMENT ON COLUMN identity_authenticator.identity_id IS '所属 Identity ID';
COMMENT ON COLUMN identity_authenticator.secret_ciphertext IS '以 AES-GCM 加密的 TOTP 密钥';
COMMENT ON COLUMN identity_authenticator.last_used_step IS '上次通过校验的 TOTP 时间步，只接受更晚的步长';
COMMENT ON COLUMN identity_authenticator.created_at IS '创建时间';
COMMENT ON COLUMN identity_authenticator.updated_at IS '最后更新时间';
COMMENT ON COLUMN identity_authenticator.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN identity_authenticator.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN identity_authenticator.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX identity_authenticator_identity_key ON identity_authenticator (identity_id) WHERE NOT deleted;

-- 只存 Recovery Code 的 SHA-256 哈希；用后或重新生成时逻辑删除。
CREATE TABLE identity_recovery_code (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES identity(id),
    code_hash varchar(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE identity_recovery_code IS 'Identity 的 Recovery Code：用后或重新生成时逻辑删除';
COMMENT ON COLUMN identity_recovery_code.id IS '主键，UUIDv7';
COMMENT ON COLUMN identity_recovery_code.identity_id IS '所属 Identity ID';
COMMENT ON COLUMN identity_recovery_code.code_hash IS 'Recovery Code 的 SHA-256 哈希';
COMMENT ON COLUMN identity_recovery_code.created_at IS '创建时间';
COMMENT ON COLUMN identity_recovery_code.updated_at IS '最后更新时间';
COMMENT ON COLUMN identity_recovery_code.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN identity_recovery_code.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN identity_recovery_code.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX identity_recovery_code_key ON identity_recovery_code (identity_id, code_hash) WHERE NOT deleted;
