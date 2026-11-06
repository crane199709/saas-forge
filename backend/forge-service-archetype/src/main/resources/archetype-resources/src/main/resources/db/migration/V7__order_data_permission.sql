#set( $dollar = '$' )
#set( $hash = '#' )
-- 订单作为 Data Resource（工单 18）：记录数据归属的 Membership 与部门路径，供 Data Scope「本人」「本部门」「本部门及下级」过滤。
-- 存量订单两列为空：只有「全部」范围的成员能看到，其余范围下不可见（SQL 中与 NULL 比较不成立）。
ALTER TABLE orders
    ADD COLUMN owner_id uuid,
    ADD COLUMN department_path ltree;
COMMENT ON COLUMN orders.owner_id IS '数据归属的 Membership ID，供「本人」Data Scope 过滤，存量订单为 NULL';
COMMENT ON COLUMN orders.department_path IS '数据归属的部门路径，供「本部门」「本部门及下级」Data Scope 过滤，存量订单为 NULL';
CREATE INDEX orders_owner_id_idx ON orders (owner_id);
-- GiST 索引支持 ltree 的 <@ 子树查询（「本部门及下级」）
CREATE INDEX orders_department_path_idx ON orders USING gist (department_path);
