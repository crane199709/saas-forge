-- 只依据旧业务确认后保存的引用归类，包含逻辑删除行；文件扩展名不能证明用途。
-- 本固定样例的可信租户清单在 HTTP 夹具中声明；真实工程须用自己的清单逐租户执行。
-- 迁移账号与运行账号都保持 FORCE RLS，不扩大权限或关闭策略。
SELECT set_config('app.tenant_id', '019a0110-0000-7000-8000-000000000001', true);
UPDATE forge_storage_object SET purpose = 'document-attachment'
WHERE purpose = 'unspecified' AND EXISTS (
    SELECT 1 FROM document_files d
    WHERE d.object_id = forge_storage_object.id AND d.tenant_id = forge_storage_object.tenant_id
);
SELECT set_config('app.tenant_id', '019a0110-0000-7000-8000-000000000011', true);
UPDATE forge_storage_object SET purpose = 'document-attachment'
WHERE purpose = 'unspecified' AND EXISTS (
    SELECT 1 FROM document_files d
    WHERE d.object_id = forge_storage_object.id AND d.tenant_id = forge_storage_object.tenant_id
);
SELECT set_config('app.tenant_id', '', true);
