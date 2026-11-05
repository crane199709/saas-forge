-- 账号安全工单 04：Operator 的 Authenticator 与 Recovery Code。与 Identity 的分表存储（ADR-0003），列与约束含义同 V19。

CREATE TABLE operator_authenticator (
    id uuid PRIMARY KEY,
    operator_id uuid NOT NULL REFERENCES operator(id),
    secret_ciphertext bytea NOT NULL,
    last_used_step bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX operator_authenticator_operator_key ON operator_authenticator (operator_id) WHERE NOT deleted;

CREATE TABLE operator_recovery_code (
    id uuid PRIMARY KEY,
    operator_id uuid NOT NULL REFERENCES operator(id),
    code_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX operator_recovery_code_key ON operator_recovery_code (operator_id, code_hash) WHERE NOT deleted;
