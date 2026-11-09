# 02: Role、Department、Position 恢复

**What to build:** 按 spec 第 3 节实现 IAM 三类资源的已删除列表和恢复接口。

**Blocked by:** 01

**Status:** resolved

- [x] Role 删除不再连带删除授权和数据策略，已删除角色的配置不影响权限计算
- [x] Role 恢复后授权和数据策略完整，且不授予任何成员权限
- [x] Department 父部门已删除时被拒绝，父部门移动后恢复能得到正确路径
- [x] Role 同租户编码冲突时返回 `resource.restoreConflict`；Department、Position 沿用同租户允许重名的规则
- [x] 恢复记录审计，跨租户恢复被拒绝，并发恢复只成功一次

## Comments

- 2026-11-09：用户确认沿用现有唯一性规则；部门、岗位在同租户内允许重名，角色只检查编码冲突，不增加仅恢复时适用的名称限制。


## Answer

完成 IAM Role、Department、Position 的已删除列表与恢复；未修改前端、demo-service 或 IAM 安全链。

### API 契约与行为

- 现有 `GET /roles`、`GET /org/departments`、`GET /org/positions` 默认只返回未删除记录；传 `deleted=true` 时只返回本租户已删除记录，按 `updated_at DESC, id` 排序。各响应仍为 record，新增 `updatedAt`、`updatedBy`，分别用于删除时间与操作人显示。
- `POST /roles/{id}/restore`、`POST /org/departments/{id}/restore`、`POST /org/positions/{id}/restore` 无请求体；成功为 HTTP 200 的 `ApiResponse.ok()`。沿用 `iam:role:manage` / `iam:org:manage` 权限。其他租户、不存在、未删除或重复恢复均返回各资源原有 404；无权限为 403。
- 专用 Mapper XML 明确限定 Token 租户与 `deleted = true`；恢复只刷新 `updated_at` / `updated_by` 和删除状态，部门另更新路径，不修改 `created_*`。Role 使用现有 `role:<tenant>` 事务咨询锁，Department、Position 使用现有 `organization:<tenant>` 锁。
- Role 删除保留 Permission Code 授权与 Data Policy，现有授权/策略计算 SQL 继续过滤 `NOT r.deleted`。恢复没有成员分配写入，不自动授予成员；恢复后常规列表重新显示保留的配置。上线前已删除且配置已被清掉的 Role 仍按现状恢复为空配置。
- Role 只检查实际部分唯一索引对应的同租户编码；冲突为 409 `resource.restoreConflict`，数据库兜底仅转换 SQLState 23505 且索引名为 `role_tenant_code_key` 的异常。异租户同编码允许。Department、Position 保持允许同名，不增加名称唯一性。
- Department 父部门仍删除时为 409 `resource.restoreBlocked`；恢复按父部门当前路径重算。与父删除、移动串行，避免出现有效子部门挂在已删除父部门下，或沿用父移动前的旧路径。
- 沿用 IAM 显式 `AuditEvent` / `TenantActor`，记录 `role.restore`、`department.restore`、`position.restore` 的成功和失败（含错误码）、租户、操作人和资源 ID。IAM 现有自定义安全链没有绑定 `CurrentTenant`，直接套 `@AuditLog` 会误记为 Platform Realm，因此未为此改动整个安全链。

### TDD 与验证

按已确认的 HTTP 集成公开边界逐片 red → green：

1. Role 已删除列表缺失（目标行不存在）→ 专用列表、审计列、恢复及配置保留通过。
2. Role 编码冲突原返回 500 → 同租户冲突 409、异租户同编码恢复成功。
3. Department 已删除列表缺失 → 父移动后按当前路径恢复，同名不冲突。
4. Department 父删除原返回 404 → 统一受阻 409，先恢复父后可恢复子。
5. Position 已删除列表缺失 → 同名岗位仍可恢复。
6. 恢复审计查询原为空 → 三类资源的成功、失败、操作人和跨租户审计查询通过。沿用现有 AuditTest 的 Outbox 回放替代 MQ 传输，断言仍通过公开审计 HTTP 接口；未启动真实 RocketMQ 验证投递。

在现有公开边界继续验证三类资源并发恢复恰好一个成功、跨租户不可见/不可恢复、普通成员拒绝、重复恢复拒绝；Department 恢复与父删除/移动竞争保持公开可见结构一致；Role 删除期间不显示授权、成员不持有该角色，恢复后成员列表仍为空且原成员的 `/auth/access` 不重新获得权限，恢复时间晚于删除时间。Data Policy 恢复完整由角色 HTTP 配置验证，其计算路径保留现有 `NOT r.deleted` 过滤并通过原有策略集成用例。

验证命令与结论：

- `cd backend && ./mvnw -o -pl forge-iam -am install -DskipTests`：通过，先安装当前 SDK，避免本地旧制品。
- `cd backend && ./mvnw -o -pl forge-iam spotless:apply`：通过。
- `cd backend && ./mvnw -o -pl forge-iam test -Dtest=RoleManagementTest,OrganizationTest,RoleImpactPreviewTest`：26 tests，0 failures/errors/skips。
- `cd backend && ./mvnw -o -pl forge-iam test -Dtest=ArchitectureTest`：1 test，0 failures/errors/skips。
- `git diff --check`：通过。

IAM 全模块交付回归与页面 Playwright 由集成工单统一执行；本工单未重复运行全模块。
