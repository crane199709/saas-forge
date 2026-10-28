# 18: 数据权限端到端（Data Scope 预设）

**What to build:** Business Service 在 Resource Manifest 中声明 Data Resource 及其可用属性（映射到表列）；Tenant Admin 在 Role 上为某 Data Resource 选择 Data Scope（本人、本部门、本部门及下级、全部）。demo-service 的订单列表、更新、删除自动按当前成员的数据范围过滤，单条操作也做校验；多个 Role 的范围取并集。

**Blocked by:** 04 ABAC 表达式树引擎, 05 租户数据隔离, 13 组织模型, 16 租户角色管理

**Status:** ready-for-agent

- [x] Resource Manifest 支持声明 Data Resource、属性、属性类型与对应表列
- [x] Data Policy 挂在 Role 上并按 Data Resource 存储
- [x] SDK 数据权限拦截器把当前成员的策略编译为 SQL 谓词，注入列表、更新、删除语句
- [x] SDK 提供单对象校验 API，使用内存求值，与 SQL 过滤结论一致
- [x] 未配置任何策略的 Data Resource 默认行为明确（拒绝访问）并在文档中说明
- [x] 策略变更递增受影响 Membership 的 version，即时生效
- [x] tenant-app 在角色编辑中提供 Data Scope 选择
- [x] Testcontainers 测试：不同部门、不同角色组合的成员看到的订单集合符合预期；越权更新、删除被拒


## Comments

2026-10-28 实现：
- 清单：`ResourceManifest` 新增可选的 `dataResources`（`DataResource(code, name, table, attributes)`，属性 `DataAttribute(code, name, type, column)`，类型 `string number boolean uuid ltree`）。校验编码、表名不重复，表名、列名只允许普通标识符；为空时不输出该字段，已存储的旧清单 JSON 比较不受影响。资源编码全平台唯一，被其他服务占用时 409 `manifest.dataResourceConflict`。`ResourceManifestCollector.dataResources(ClassLoader)` 只读取清单文件中的数据资源，供拦截器使用。demo 声明 `order`（`ownerId`、`departmentPath`、`amount`），清单版本升到 3。
- 引擎：forge-sdk-abac 新增 `DataResourceDefinition`。它把平台主体属性与资源属性组成属性目录和列映射，并给出资源可用的 Data Scope 预设（预设引用的属性都已声明时才可用）。iam 校验策略与 SDK 编译、求值使用同一份目录。
- 存储与发布：iam V13 新增纯关联表 `role_data_policy (role_id, resource, scope)`，每个角色每个资源一条，本期只存预设名（`self department department_and_below all`），自定义条件留给工单 19。`MembershipGrants.dataPolicies` 按资源编码给出各角色策略的 JSON 表达式树，与权限同版本发布到 Redis。Owner 与 Tenant Admin 在全部资源上为「全部」。只发布当前清单中仍声明的资源：资源下线后策略保留但不发布，整体替换时也不改动。接口：`GET /roles/data-resources`、`PUT /roles/{id}/data-policies`（资源未声明、重复或不支持该预设时 400 `role.invalidDataPolicy`，预置角色 409），`GET /roles` 带 `dataPolicies`。策略变更在事务提交后递增持有者的 version。
- SDK 拦截：forge-sdk-abac 的 `data` 包。`DataPermissionInnerInterceptor` 基于 MyBatis-Plus 多表拦截基类，对数据资源表的 SELECT（含 JOIN 与子查询）、UPDATE、DELETE 追加当前成员策略的并集。编译出的 `?` 先换成命名标记，交给 JSqlParser 合并，再按最终 SQL 中的位置插入参数映射，值一律参数绑定。它是独立的 `MybatisPlusInterceptor` Bean，不改租户拦截器。`@InterceptorIgnore(dataPermission = "true")` 可豁免单个 Mapper 方法（demo 的租户清理）。单对象校验 `DataPermission.permits/check` 用内存求值，`currentSubject()` 给出当前成员的主体属性；另提供写 ltree 列的 `LtreeTypeHandler`。相关依赖均为 optional，iam 只使用引擎，不会引入资源服务器自动配置。
- 默认行为（见 docs/data-permission.md）：成员在某资源上没有任何策略时拒绝访问。非成员的已认证请求一律拒绝（TODO #22）。没有认证上下文的系统操作不受数据权限限制。没有 Redis 明细来源时，成员请求以 500 失败。超出范围的对象与不存在的对象一样返回 404。
- demo：V7 给 `orders` 加 `owner_id` 与 `department_path ltree`（GiST 索引），存量行两列为空，只有「全部」范围可见。创建订单时归属当前成员与其主部门，并先做单对象校验，超出自身范围返回 403，避免创建出自己看不到的数据。响应带 `ownerId`、`departmentPath`。
- 前端：tenant-app 角色编辑对话框为每个数据资源提供「未配置（不可见）」以及该资源可用的预设单选，列表新增「数据范围」列，预置角色显示「全部数据」。新建角色时先建角色再提交数据范围，两步不在同一事务，第二步失败时角色已建好但没有范围，可再次编辑。
- 已知取舍：业务服务新增或下线数据资源时不递增 version，成员下一次刷新 Token 后生效，与权限码一致。语句中如果出现 PostgreSQL 的 `?` 运算符（jsonb）或 `$$` 字符串，占位符计数会对不上，拦截器会抛错，不会放行。demo 测试直接向 Redis 发布明细来模拟 iam，iam 到 Redis 的发布由 iam 测试单独覆盖，未以真实服务串联手动验证。
- 验证：forge-sdk-manifest 新增数据资源解析与校验 1 项；forge-sdk-abac 新增 `DataResourceDefinitionTest` 2 项、拦截器参数绑定 2 项；iam `RoleManagementTest` 新增 1 项（资源与可用预设、多角色并集发布、变更递增 version、预置角色全部、非法策略、下线资源策略保留、编码冲突）；demo `DataPermissionTest` 4 项（不同部门与角色组合看到的订单集合及总数、本部门不含下级、兼任部门、并集、无策略默认拒绝、越权读/改/删 404 且数据不变、范围外创建 403、单对象校验与 SQL 过滤逐单一致）。后端 `./mvnw verify` 全部通过：网关 `TenantRateLimitTest` 首轮失败一次，未改动网关，单独重跑通过；IDE 增量编译写坏的 test-classes 清理后重跑通过。前端 76 项单测（新增数据范围 1 项）、typecheck、format:check 通过；未运行 Playwright，也未在浏览器中手动操作。
