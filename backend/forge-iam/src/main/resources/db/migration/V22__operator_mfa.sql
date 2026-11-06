-- 账号安全工单 04：Operator 的 Authenticator 与 Recovery Code。与 Identity 的分表存储（ADR-0003），列与约束含义同 V19。

CREATE TABLE operator_authenticator (
    id uuid PRIMARY KEY,
    operator_id uuid NOT NULL REFERENCES operator(id),
    secret_ciphertext bytea NOT NULL,
    last_used_step bigint NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE operator_authenticator IS 'Operator 的 Authenticator：每个 Operator 至多一个未删除的记录';
COMMENT ON COLUMN operator_authenticator.id IS 'Authenticator ID，UUIDv7';
COMMENT ON COLUMN operator_authenticator.operator_id IS '所属 Operator ID';
COMMENT ON COLUMN operator_authenticator.secret_ciphertext IS '以 AES-GCM 加密的 TOTP 密钥';
COMMENT ON COLUMN operator_authenticator.last_used_step IS '上次通过校验的 TOTP 时间步，只接受更晚的步长';
COMMENT ON COLUMN operator_authenticator.created_at IS '创建时间';
COMMENT ON COLUMN operator_authenticator.updated_at IS '最后更新时间';
COMMENT ON COLUMN operator_authenticator.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN operator_authenticator.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN operator_authenticator.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX operator_authenticator_operator_key ON operator_authenticator (operator_id) WHERE NOT deleted;

CREATE TABLE operator_recovery_code (
    id uuid PRIMARY KEY,
    operator_id uuid NOT NULL REFERENCES operator(id),
    code_hash varchar(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE operator_recovery_code IS 'Operator 的 Recovery Code：用后或重新生成时逻辑删除';
COMMENT ON COLUMN operator_recovery_code.id IS '主键，UUIDv7';
COMMENT ON COLUMN operator_recovery_code.operator_id IS '所属 Operator ID';
COMMENT ON COLUMN operator_recovery_code.code_hash IS 'Recovery Code 的 SHA-256 哈希';
COMMENT ON COLUMN operator_recovery_code.created_at IS '创建时间';
COMMENT ON COLUMN operator_recovery_code.updated_at IS '最后更新时间';
COMMENT ON COLUMN operator_recovery_code.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN operator_recovery_code.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN operator_recovery_code.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE UNIQUE INDEX operator_recovery_code_key ON operator_recovery_code (operator_id, code_hash) WHERE NOT deleted;
