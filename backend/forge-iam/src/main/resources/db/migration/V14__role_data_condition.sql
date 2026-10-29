-- 角色上的自定义条件（工单 19）：scope 为 custom 时 condition 存可视化编辑器生成的 JSON 表达式树，已经 iam 按资源与租户的
-- 扩展属性校验；预设不存 condition，模板随 SDK 版本演进。每个角色在每个资源上仍至多一条，预设与自定义条件二选一。
ALTER TABLE role_data_policy DROP CONSTRAINT role_data_policy_scope_check;
ALTER TABLE role_data_policy ADD COLUMN condition jsonb;
ALTER TABLE role_data_policy ADD CONSTRAINT role_data_policy_scope_check CHECK (
    (scope IN ('self', 'department', 'department_and_below', 'all') AND condition IS NULL)
    OR (scope = 'custom' AND jsonb_typeof(condition) = 'object'));
