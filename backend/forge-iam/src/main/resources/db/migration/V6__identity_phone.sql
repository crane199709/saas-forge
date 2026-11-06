-- 自主注册只要求验证邮箱或手机之一（工单 10），邮箱改为可空，并新增全局唯一的手机号。
ALTER TABLE identity ALTER COLUMN email DROP NOT NULL;
ALTER TABLE identity ADD COLUMN phone varchar(16) CONSTRAINT identity_phone_format CHECK (phone ~ '^\+?[0-9]{6,15}$');
COMMENT ON COLUMN identity.phone IS '手机号，全局唯一，与邮箱至少有其一';
ALTER TABLE identity ADD CONSTRAINT identity_contact_required CHECK (email IS NOT NULL OR phone IS NOT NULL);
CREATE UNIQUE INDEX identity_phone_key ON identity (phone) WHERE NOT deleted;
