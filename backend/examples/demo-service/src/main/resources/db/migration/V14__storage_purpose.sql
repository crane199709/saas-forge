-- 已接入服务复制到新的 Flyway 迁移；不要修改已执行过的初始建表迁移。
-- ADD COLUMN 的默认值回填全部存量对象，随后移除默认值，要求新登记显式提供用途。
ALTER TABLE forge_storage_object ADD COLUMN purpose varchar(64) NOT NULL DEFAULT 'unspecified';
COMMENT ON COLUMN forge_storage_object.purpose IS '存储用途编码，unspecified 表示尚未归类的存量对象';
ALTER TABLE forge_storage_object ALTER COLUMN purpose DROP DEFAULT;
-- unspecified 不能作为 StoragePurpose 声明，SDK 下载时会返回 storage.purposeMismatch。
-- 只有服务能根据可信业务关联确定实际用途；在单独迁移中显式归类，未知对象保持拒绝。
