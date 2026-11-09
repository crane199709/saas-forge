-- 仅供更换套餐影响预览测试：套餐上线前创建、未分配套餐的租户，以及 App Registry 中的 Sub-App。插入须可重复执行。
INSERT INTO tenant (id, name, status, admin_contact)
VALUES ('019a0210-0000-7000-8000-000000000001', '未分配套餐的租户', 'active', 'legacy@example.com')
ON CONFLICT DO NOTHING;
INSERT INTO sub_app (id, code, name, version, revision) VALUES
('019a0210-0000-7000-8000-000000000011', 'impact-registered', '已登记应用', '1.0.0', 1),
('019a0210-0000-7000-8000-000000000012', 'impact-kept', '保留应用', '1.0.0', 1)
ON CONFLICT DO NOTHING;
