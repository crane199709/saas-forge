-- 已有对象保留原上传状态，不追溯应用新保留规则；原 STORED 对象继续永久保留。
ALTER TABLE forge_storage_object ADD COLUMN retention_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE forge_storage_object ADD COLUMN expired_at TIMESTAMP WITH TIME ZONE;
COMMENT ON COLUMN forge_storage_object.retention_expires_at IS '服务声明的保留截止时间，NULL 表示永久保留';
COMMENT ON COLUMN forge_storage_object.expired_at IS '实际失效时间，七天宽限期从此时起算';
COMMENT ON COLUMN forge_storage_object.status IS '状态：PENDING 待确认，STORED 可读取，EXPIRED 已失效，PURGE_PENDING 待删除，DELETED 已删除，HELD 预留冻结';
COMMENT ON COLUMN forge_storage_object.upload_expires_at IS '上传地址过期时间，未确认对象此后可进入失效治理';
