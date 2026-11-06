-- 订单列表按创建时间排序；已有行以迁移时间补齐。
ALTER TABLE orders ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
COMMENT ON COLUMN orders.created_at IS '创建时间';
