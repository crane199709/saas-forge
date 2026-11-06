ALTER TABLE orders ADD COLUMN status varchar(16) NOT NULL DEFAULT 'unpaid'
    CHECK (status IN ('unpaid', 'closed'));
COMMENT ON COLUMN orders.status IS '订单状态：unpaid 未支付，closed 已关闭';
