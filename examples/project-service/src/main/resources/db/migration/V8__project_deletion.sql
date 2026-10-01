-- 永久删除由既有 FORCE RLS 与非级联复合外键共同约束；不增加其他权限。
GRANT DELETE ON projects TO project_app;
