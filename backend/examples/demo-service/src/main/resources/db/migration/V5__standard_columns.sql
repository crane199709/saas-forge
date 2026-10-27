-- 补齐标准列（建表标准列规格第 3 节）：存量行的时间取迁移执行时间，操作人为 NULL，均未删除。
-- orders.created_at 已由 V3 按标准定义，这里不再改动。
ALTER TABLE orders
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;

ALTER TABLE order_notifications
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;
