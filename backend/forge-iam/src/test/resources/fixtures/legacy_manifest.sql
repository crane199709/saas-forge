-- 模拟 actions 引入前保存的完整序列化内容，避免用新模型生成旧版本夹具。
INSERT INTO resource_manifest (id, service, version, content, current)
VALUES ('019a0110-0000-7000-8000-0000000000b1', 'demo-service', 'legacy-actions',
'{"service":"demo-service","version":"legacy-actions","permissions":[{"code":"order:read","name":"查看订单"}],"menus":[{"code":"legacy","name":"旧菜单","app":"demo","path":"legacy","permission":"order:read","children":[]}]}', false);
