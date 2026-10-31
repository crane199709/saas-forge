-- 仅用于隔离 Testcontainers 中的授权树套餐裁剪场景。
INSERT INTO tenant_plan (tenant_id, plan_id, plan_version) VALUES ('019a0110-0000-7000-8000-0000000000c1', '019a0110-0000-7000-8000-0000000000d1', 1);
INSERT INTO tenant_plan_permission (tenant_id, code) VALUES
('019a0110-0000-7000-8000-0000000000c1', 'pt:read'),
('019a0110-0000-7000-8000-0000000000c1', 'pt:edit'),
('019a0110-0000-7000-8000-0000000000c1', 'pt:orphan');
INSERT INTO tenant_plan_sub_app (tenant_id, code) VALUES ('019a0110-0000-7000-8000-0000000000c1', 'demo');
