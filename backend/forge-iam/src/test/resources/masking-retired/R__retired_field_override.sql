-- 模拟字段从当前清单移除后保留的历史覆盖；它不再构成有效引用。
INSERT INTO tenant (id, name) VALUES ('019a0130-0000-7000-8000-000000000001', '历史覆盖租户');
SELECT set_config('app.tenant_id', '019a0130-0000-7000-8000-000000000001', false);
INSERT INTO field_masking_override (id, tenant_id, service, path, rule_code)
VALUES ('019a0130-0000-7000-8000-000000000002', '019a0130-0000-7000-8000-000000000001', 'retired-service', 'person.retired', 'retired-rule');
SELECT set_config('app.tenant_id', '', false);
