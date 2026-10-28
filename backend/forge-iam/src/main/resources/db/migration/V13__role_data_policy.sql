-- 角色上的 Data Policy（工单 18），按 Data Resource 存储，每个角色在每个资源上至多一条。纯关联表，不带标准列；
-- 租户经 role.tenant_id 限定。本期只支持 Data Scope 预设，自定义条件留给工单 19。
-- resource 是业务服务清单中的 Data Resource 编码，不建外键：资源从清单中消失后保留策略但不发布，重新声明后随之恢复。
CREATE TABLE role_data_policy (
    role_id uuid NOT NULL REFERENCES role(id),
    resource varchar(64) NOT NULL,
    scope varchar(32) NOT NULL CHECK (scope IN ('self', 'department', 'department_and_below', 'all')),
    PRIMARY KEY (role_id, resource)
);
