-- 账号安全工单 03：Tenant 的 MFA Requirement，默认关闭。由 Tenant Admin 在 iam 中设置，platform 的 Tenant 主数据不含此项；
-- iam 在签发进入该租户的 Token 时读取（ADR-0019）。
ALTER TABLE tenant ADD COLUMN mfa_required boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN tenant.mfa_required IS 'MFA Requirement：是否要求进入该租户的 Identity 完成 MFA，默认关闭';
