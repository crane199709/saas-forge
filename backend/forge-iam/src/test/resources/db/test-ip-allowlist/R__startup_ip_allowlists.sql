-- 启动发布须从持久化值恢复，而不是将全部 Tenant 当作未启用。
INSERT INTO tenant (id, name, ip_allowlist) VALUES
('019a0190-0000-7000-8000-000000000003', '启动时恢复限制规则', '2001:db8::/32'),
('019a0190-0000-7000-8000-000000000004', '启动时恢复无限制', '*') ON CONFLICT DO NOTHING;
