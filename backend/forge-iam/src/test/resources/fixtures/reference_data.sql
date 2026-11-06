-- 仅供参考数据集成测试：演示租户中一个 Tenant Admin 与一个普通成员，另一个租户中一个 Tenant Admin；
-- 服务 refdata-service 的两个清单版本，v2 为当前版本，v1 中的字典项 legacy 与参数 order.removed 在 v2 中被删除。插入须可重复执行。
INSERT INTO tenant (id, name) VALUES ('019a0110-0000-7000-8000-0000000002c1', '参考数据测试租户 B') ON CONFLICT DO NOTHING;
INSERT INTO identity (id, account, email, display_name, password_hash)
SELECT v.id, v.account, v.email, v.display_name, i.password_hash
FROM identity i CROSS JOIN (VALUES
    (CAST('019a0110-0000-7000-8000-0000000002c2' AS uuid), 'refadmin', 'refadmin@example.com', '参考数据管理员'),
    (CAST('019a0110-0000-7000-8000-0000000002c4' AS uuid), 'refmember', 'refmember@example.com', '参考数据成员'),
    (CAST('019a0110-0000-7000-8000-0000000002c6' AS uuid), 'refadminb', 'refadminb@example.com', '租户 B 管理员')
) AS v (id, account, email, display_name)
WHERE i.account = 'demo' ON CONFLICT DO NOTHING;
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a0110-0000-7000-8000-0000000002c3', '019a0110-0000-7000-8000-0000000002c2', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000002c5', '019a0110-0000-7000-8000-0000000002c4', '019a0110-0000-7000-8000-000000000001'),
('019a0110-0000-7000-8000-0000000002c7', '019a0110-0000-7000-8000-0000000002c6', '019a0110-0000-7000-8000-0000000002c1')
ON CONFLICT DO NOTHING;
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a0110-0000-7000-8000-0000000002c8', '019a0110-0000-7000-8000-0000000002c1', 'tenant-admin', '租户管理员', true)
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
SELECT '019a0110-0000-7000-8000-0000000002c3', id FROM role
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'tenant-admin' AND NOT deleted
ON CONFLICT DO NOTHING;
INSERT INTO membership_role (membership_id, role_id)
VALUES ('019a0110-0000-7000-8000-0000000002c7', '019a0110-0000-7000-8000-0000000002c8')
ON CONFLICT DO NOTHING;

INSERT INTO resource_manifest (id, service, version, content, current, created_at)
VALUES ('019a0110-0000-7000-8000-0000000002d1', 'refdata-service', '1',
'{"service":"refdata-service","version":"1","permissions":[],"menus":[],
  "dictionaries":[{"code":"customer-source","name":"客户来源","extensible":true,"items":[
    {"code":"online","name":"线上"},{"code":"legacy","name":"旧渠道"},{"code":"referral","name":"转介绍"}]}],
  "parameters":[{"code":"order.removed","name":"已删除的参数","type":"integer","default":1,"tenantEditable":true}]}',
false, CURRENT_TIMESTAMP - INTERVAL '1' DAY)
ON CONFLICT DO NOTHING;
INSERT INTO resource_manifest (id, service, version, content, current)
VALUES ('019a0110-0000-7000-8000-0000000002d2', 'refdata-service', '2',
'{"service":"refdata-service","version":"2","permissions":[],"menus":[],
  "dictionaries":[
    {"code":"customer-source","name":"客户来源","extensible":true,"items":[
      {"code":"online","name":"线上"},{"code":"referral","name":"转介绍"}]},
    {"code":"order-status","name":"订单状态","items":[{"code":"open","name":"未完成"},{"code":"closed","name":"已关闭"}]},
    {"code":"grade","name":"客户等级","extensible":true,"items":[{"code":"normal","name":"普通"}]},
    {"code":"channel","name":"渠道","extensible":true,"items":[{"code":"web","name":"网站"},{"code":"store","name":"门店"}]},
    {"code":"level","name":"级别","extensible":true,"items":[{"code":"low","name":"低"},{"code":"high","name":"高"}]},
    {"code":"priority","name":"优先级","extensible":true,"items":[
      {"code":"p-low","name":"低优先级"},{"code":"p-mid","name":"中优先级"},{"code":"p-high","name":"高优先级"}]},
    {"code":"region","name":"区域","extensible":true,"items":[{"code":"north","name":"北方"},{"code":"south","name":"南方"}]}],
  "parameters":[
    {"code":"order.auto-close","name":"未支付订单自动关闭时长","type":"duration","default":"PT30M","min":"PT5M","max":"P3D","tenantEditable":true},
    {"code":"order.max-items","name":"单笔订单最多商品数","type":"integer","default":50,"min":1,"max":200,"tenantEditable":true},
    {"code":"order.remark","name":"订单备注模板","type":"string","default":"请尽快付款","maxLength":10},
    {"code":"customer.default-source","name":"默认客户来源","type":"dictionary","dictionary":"customer-source","default":"online","tenantEditable":true},
    {"code":"ticket.priority","name":"工单默认优先级","type":"dictionary","dictionary":"priority","default":"p-low","tenantEditable":true},
    {"code":"ticket.escalation","name":"工单升级优先级","type":"dictionary","dictionary":"priority","default":"p-high"}]}',
true)
ON CONFLICT DO NOTHING;
-- 被新版本删除的参数上保留的覆盖值。
INSERT INTO parameter_override (id, tenant_id, service, code, value)
VALUES ('019a0110-0000-7000-8000-0000000002e1', '019a0110-0000-7000-8000-000000000001', 'refdata-service', 'order.removed', '5')
ON CONFLICT DO NOTHING;
-- 区域字典中与内置项同名的自定义项：模拟服务新版本新增的内置项与租户已有的可选项重名；另有一个与内置项同名但已停用的自定义项。
INSERT INTO dictionary_item (id, tenant_id, service, dictionary, code, custom, name, sort_order, enabled) VALUES
('019a0110-0000-7000-8000-0000000002f1', '019a0110-0000-7000-8000-000000000001', 'refdata-service', 'region', 'custom.north', true, '北方', 3, true),
('019a0110-0000-7000-8000-0000000002f2', '019a0110-0000-7000-8000-000000000001', 'refdata-service', 'region', 'custom.south', true, '南方', 4, false)
ON CONFLICT DO NOTHING;
