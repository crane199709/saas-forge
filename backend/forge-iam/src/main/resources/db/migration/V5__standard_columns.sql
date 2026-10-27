-- 补齐标准列（建表标准列规格第 3 节）：存量行的时间取迁移执行时间，操作人为 NULL，均未删除。
ALTER TABLE tenant
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;

ALTER TABLE identity
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;

ALTER TABLE membership
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;

ALTER TABLE operator
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;

-- 唯一性改为只约束未删除的行，账号删除后可重新注册、成员移除后可再次加入。CHECK 约束不变。
ALTER TABLE identity DROP CONSTRAINT identity_account_key;
CREATE UNIQUE INDEX identity_account_key ON identity (account) WHERE NOT deleted;
DROP INDEX identity_email_lower_key;
CREATE UNIQUE INDEX identity_email_lower_key ON identity (lower(email)) WHERE NOT deleted;
ALTER TABLE membership DROP CONSTRAINT membership_identity_id_tenant_id_key;
CREATE UNIQUE INDEX membership_identity_id_tenant_id_key ON membership (identity_id, tenant_id) WHERE NOT deleted;
ALTER TABLE operator DROP CONSTRAINT operator_account_key;
CREATE UNIQUE INDEX operator_account_key ON operator (account) WHERE NOT deleted;
