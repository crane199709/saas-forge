-- 仅授权本票维护的可变业务字段；Tenant、标识和创建时间仍不可更新。
GRANT UPDATE (name, description, version, updated_at) ON projects TO project_app;
