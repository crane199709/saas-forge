-- 账号安全工单 01：Identity 的 Authenticator 与 Recovery Code。Operator 的另建表（ADR-0003），Authenticator 属于全局 Identity，
-- 在其全部 Tenant 通用（ADR-0019），因此没有 tenant_id 与 RLS。

-- 每个 Identity 至多一个未删除的 Authenticator。密钥以 AES-GCM 加密存放（加密密钥见 forge.iam.mfa.encryption-key）；
-- last_used_step 是上次通过校验的 TOTP 时间步，只接受更晚的步长，同一动态码因此只能用一次。
CREATE TABLE identity_authenticator (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES identity(id),
    secret_ciphertext bytea NOT NULL,
    last_used_step bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX identity_authenticator_identity_key ON identity_authenticator (identity_id) WHERE NOT deleted;

-- 只存 Recovery Code 的 SHA-256 哈希；用后或重新生成时逻辑删除。
CREATE TABLE identity_recovery_code (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES identity(id),
    code_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX identity_recovery_code_key ON identity_recovery_code (identity_id, code_hash) WHERE NOT deleted;
