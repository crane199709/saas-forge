-- 补齐标准列（建表标准列规格第 3 节）：存量行的时间取迁移执行时间，操作人为 NULL，均未删除。
-- orders.created_at 已由 V3 按标准定义，这里不再改动。
ALTER TABLE orders
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN orders.updated_at IS '最后更新时间';
COMMENT ON COLUMN orders.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN orders.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN orders.deleted IS '逻辑删除标记，true 表示对常规查询不可见';

ALTER TABLE order_notifications
    ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_by uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN order_notifications.created_at IS '创建时间';
COMMENT ON COLUMN order_notifications.updated_at IS '最后更新时间';
COMMENT ON COLUMN order_notifications.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_notifications.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN order_notifications.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
