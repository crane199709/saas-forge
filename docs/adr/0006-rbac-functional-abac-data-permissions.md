# 功能权限用 RBAC，数据权限用自研 ABAC 表达式树

功能权限（菜单、按钮、接口）用 RBAC：Role 绑定 Permission Code。数据权限用 ABAC：Data Policy 挂在 Role 上，以主体属性（部门、岗位、扩展属性）、资源属性（行上的列）与环境属性组成条件。策略存为自研的结构化 JSON 表达式树，同一棵树有两个执行器：编译为 SQL 谓词（经 MyBatis-Plus 拦截器注入列表查询）与内存求值（单对象校验），两者结果必须一致并有测试保证。部门层级用 PostgreSQL `ltree` 存储，使「本部门及下级」成为高效谓词。

## Considered Options

- 纯 ABAC（功能权限也用策略表达）：管理界面难做，可解释性差。
- Casbin / OPA / Cedar 等通用引擎：擅长回答「能否做」，难以稳定地把策略编译成 SQL 过滤列表，而列表过滤恰是核心需求。
