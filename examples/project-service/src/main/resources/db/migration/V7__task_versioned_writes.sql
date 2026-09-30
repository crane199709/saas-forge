-- 仅开放 Task 内容、状态和服务端维护的版本/更新时间；归属与标识保持不可写。
GRANT UPDATE (title, description, status, version, updated_at), DELETE ON tasks TO project_app;
