-- 创建租户须选择套餐：各测试共用的默认套餐。插入须可重复执行。
INSERT INTO plan (id, code, name, permissions, sub_apps)
VALUES ('019a0200-0000-7000-8000-000000000001', 'default', '默认套餐', '{order:read}', '{}')
ON CONFLICT DO NOTHING;
