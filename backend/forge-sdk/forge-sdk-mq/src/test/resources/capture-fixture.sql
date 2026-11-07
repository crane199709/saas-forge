CREATE TABLE capture_business (value text NOT NULL);
COMMENT ON TABLE capture_business IS '死信捕获测试业务记录';
COMMENT ON COLUMN capture_business.value IS '业务写入值';

-- 注入真实数据库约束失败，验证捕获提交失败时不确认消息。
ALTER TABLE forge_dead_event ADD CONSTRAINT capture_failure CHECK (event_id <> 'write-fails');
