-- 仅供标准主链路，在 run.sh 本次临时容器中加载；专项保持各自空收件箱前提。
BEGIN;
SET search_path = iam, extensions;
INSERT INTO todo (id,tenant_id,service,business_key,opened,occurred_at,occurred_at_nanos,title,body,app_code,route) VALUES
('019a2525-0000-7000-8000-000000000071','019a2525-0000-7000-8000-000000000001','demo-service','workbench:a',true,CURRENT_TIMESTAMP,0,'A 待处理订单','请核对租户 A 的订单资料。','demo','/overview'),
('019a2525-0000-7000-8000-000000000072','019a2525-0000-7000-8000-000000000002','demo-service','workbench:b',true,CURRENT_TIMESTAMP,0,'B 待处理订单','请核对租户 B 的订单资料。','demo','/overview');
INSERT INTO todo_membership (tenant_id,todo_id,membership_id) VALUES
('019a2525-0000-7000-8000-000000000001','019a2525-0000-7000-8000-000000000071','019a2525-0000-7000-8000-000000000031'),
('019a2525-0000-7000-8000-000000000002','019a2525-0000-7000-8000-000000000072','019a2525-0000-7000-8000-000000000032');
-- 使用当前时间，落入迁移为本月创建的通知分区。
INSERT INTO notification (id,occurred_at,tenant_id,service,title,body,app_code,route) VALUES
('019a2525-0000-7000-8000-000000000081',CURRENT_TIMESTAMP,'019a2525-0000-7000-8000-000000000001','demo-service','A 订单审核提醒','租户 A 的订单已提交审核。','demo','/overview'),
('019a2525-0000-7000-8000-000000000082',CURRENT_TIMESTAMP,'019a2525-0000-7000-8000-000000000002','demo-service','B 订单审核提醒','租户 B 的订单已提交审核。','demo','/overview');
INSERT INTO notification_recipient (notification_id,occurred_at,tenant_id,membership_id) VALUES
('019a2525-0000-7000-8000-000000000081',CURRENT_TIMESTAMP,'019a2525-0000-7000-8000-000000000001','019a2525-0000-7000-8000-000000000031'),
('019a2525-0000-7000-8000-000000000082',CURRENT_TIMESTAMP,'019a2525-0000-7000-8000-000000000002','019a2525-0000-7000-8000-000000000032');
INSERT INTO tenant_announcement (id,tenant_id,title,body) VALUES
('019a2525-0000-7000-8000-000000000091','019a2525-0000-7000-8000-000000000001','A 租户业务公告','租户 A 本周业务安排已发布。'),
('019a2525-0000-7000-8000-000000000092','019a2525-0000-7000-8000-000000000002','B 租户业务公告','租户 B 本周业务安排已发布。');
COMMIT;
