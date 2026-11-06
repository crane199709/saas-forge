-- 租户所在区域（工单 22），平台 Data Policy 据此限定 Operator 可见的租户，如「区域等于 Operator 负责的区域」。可为空：
-- 区域上线前创建的租户没有区域，只有「全部」范围可见。
ALTER TABLE tenant ADD COLUMN region varchar(64);
COMMENT ON COLUMN tenant.region IS '租户所在区域，平台 Data Policy 据此限定 Operator 可见的租户';
CREATE INDEX tenant_region_idx ON tenant (region) WHERE NOT deleted;
