-- 订单列表按创建时间排序；已有行以迁移时间补齐。
ALTER TABLE orders ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
