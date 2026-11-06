-- 订单的客户来源（字典、参数与编号规格第 4.1 节）：保存字典 customer-source 的字典项编码，名称由前端按字典翻译。
-- 存量订单没有客户来源，允许为空。
ALTER TABLE orders ADD COLUMN customer_source varchar(64);
COMMENT ON COLUMN orders.customer_source IS '客户来源，字典 customer-source 的字典项编码，存量订单为 NULL';
