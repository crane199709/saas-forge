-- 不改写既有身份；非法邮箱或大小写重复必须由维护者先处理，避免静默合并账号。
ALTER TABLE identity ADD CONSTRAINT identity_email_format CHECK (email LIKE '%_@_%');
CREATE UNIQUE INDEX identity_email_lower_key ON identity (lower(email));
ALTER TABLE identity DROP CONSTRAINT identity_email_key;
