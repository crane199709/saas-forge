ALTER TABLE tenant ADD COLUMN ip_allowlist text NOT NULL DEFAULT '*';
COMMENT ON COLUMN tenant.ip_allowlist IS '租户 IP 允许列表的规范化发布值，星号表示无限制';
