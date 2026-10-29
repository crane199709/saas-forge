# 20: 套餐

**What to build:** Operator 在 platform-console 维护 Plan（Feature Pack：可用 Permission Code 与 Sub-App；Quota：成员数、存储容量等）并分配给 Tenant。Tenant Admin 授权时只能在套餐范围内选择；租户降级后超出新套餐的授权自动失效但保留记录，升级后自动恢复；成员数配额生效。不做计费与支付。

**Blocked by:** 14 租户创建与开通, 16 租户角色管理

**Status:** ready-for-agent

- [x] Plan 的增删改；Feature Pack 从 Resource Manifest 中选择权限码
- [x] 创建 Tenant 时选择 Plan，之后可变更；变更经 Outbox 广播 `TenantPlanChanged`
- [x] 角色授权界面只展示套餐内的权限码；越界授权被服务端拒绝
- [x] 降级后超限授权失效（成员即时失去该权限），记录保留；升级后恢复
- [x] 成员数配额：达到上限后邀请被拒
- [x] Feature Pack 中的 Sub-App 列表可维护（加载控制在后续工单实现）
- [x] 测试：降级后成员访问超限接口得到 403，升级后恢复；超出成员配额的邀请被拒


## Comments

2026-10-29 实现：
- SDK：forge-sdk-provisioning 新增事件 `TenantPlanChanged(tenantId, planId, planCode, permissions, subApps, memberQuota, storageQuotaMb, version)`（Topic `forge-tenant`），携带变更后 Plan 的完整内容，订阅方按 `version` 只应用更新的变更。
- platform：新功能包 `plan`。V4 新增 `plan`（编码部分唯一索引；`permissions`、`sub_apps` 为 `text[]`，经 MyBatis `ArrayTypeHandler` 映射；`member_quota`、`storage_quota_mb` 为 NULL 表示不限）与 `tenant.plan_id`、`tenant.plan_version`。接口 `GET/POST /plans`、`PUT /plans/{id}`（编码不可改，整体替换名称、Feature Pack 与 Quota）、`DELETE /plans/{id}`（仍有冷静期未结束的租户使用时 409 `plan.inUse`）、`GET /plans/permissions`（权限码目录）。Feature Pack 只校验新加入的权限码须在目录中（400 `plan.unknownPermission`），已在 Plan 中、后来从清单下线的编码可以保留。Sub-App 只维护编码，加载控制留给工单 21。
- platform 租户：创建租户必须带 `planId`，与 `TenantCreated`、`TenantPlanChanged(version=1)` 同一事务；`PUT /tenants/{id}/plan` 更换套餐（相同套餐不广播）；修改 Plan 内容时对使用它的每个租户递增 `plan_version` 并广播。分配时对 plan 行加共享锁、修改/删除加排他锁，广播内容不会被并发修改覆盖。plan 功能包经接口 `PlanUsage`（由 tenant 包的 `TenantPlanService` 实现）回调租户，两个功能包之间无循环。初始状态与到期时间仍由 Operator 创建时选择：不做计费，Plan 不规定试用期（原 TODO(#20) 已移除）。
- 权限码目录：iam 新增服务间接口 `GET /internal/permissions`（与清单上报同一过滤链，以服务令牌认证；网关拒绝 `/internal/**`），返回业务服务申报的有效权限码，不含 iam 自身的管理权限码。platform 以 `forge.platform.iam.uri`、`forge.platform.iam.token` 直连读取（无代码默认值，漏配启动失败），iam 的 `forge.iam.manifest.tokens` 新增 `forge-platform`（本地默认 `local-platform-manifest-token`，环境变量 `FORGE_PLATFORM_MANIFEST_TOKEN`，两边须一致）。
- iam：新功能包 `plan`（V15 `tenant_plan` + `tenant_plan_permission`，没有记录的租户——套餐上线前创建的——不受限）。`TenantPlanChangedHandler` 按版本应用后递增该租户全部 active 成员的 version 并发布明细：降级后成员下一次请求即被网关拒绝，刷新后的明细不含超出的权限码；升级后同样恢复。有效权限 = 角色授权 ∩ 有效权限码 ∩（Plan Feature Pack ∪ iam 自身管理权限码）；Owner/Tenant Admin 取 Plan 范围内全部有效权限码。iam 的 `iam:member:manage`、`iam:org:manage`、`iam:role:manage` 不受套餐限制，Tenant Admin 始终能管理成员与角色。授权界面（`GET /roles/permissions`）与角色列表只展示 Plan 内的码；越界授权 400 `role.permissionNotInPlan`；整体替换授权只撤销 Plan 内的码，超出的记录保留，升级后恢复。
- 成员数配额：邀请在租户角色锁内比较邀请前后未离开的成员数（邀请中、正常、停用都计），新增成员会超出上限时 409 `member.quotaExceeded`；重发已有的邀请不受影响；降级到低于现有人数时不影响已有成员。
- 前端：platform-console 新增套餐面板（列表、新建/编辑对话框按服务与分组勾选目录中的权限码、已下线的码单独列出并保留、Sub-App 编码、成员数与存储容量上限、删除）；租户创建必须选择套餐，列表与详情展示套餐，详情可更换套餐。tenant-app 无需改动（授权列表已由服务端按套餐过滤），预置角色的摘要文案改为「套餐内全部权限」。
- 验证：platform `PlanTest` 2 项（目录读取、编码冲突、未知权限码、非法 Sub-App/配额；创建/更换/修改套餐的广播内容与版本、相同套餐不广播、使用中不能删除、删除后不能再分配），既有 `TenantProvisioningTest`、`TenantLifecycleTest` 改为带默认套餐创建，platform 共 10 项通过。后端 `./mvnw verify` 全部通过（iam 74 项）。iam `PlanEnforcementTest` 3 项（降级后成员 version 递增、Redis 明细与菜单不再含超出的码、Tenant Admin 只有 Plan 内的业务权限码、越界授权被拒、整体替换不撤销保留记录、旧版本事件被忽略、升级后恢复；成员数配额；`/internal/permissions` 需服务令牌）。「降级后访问超限接口得到 403」以 Redis 明细验证——业务服务的 `@RequiresPermission` 读取的就是这份明细，按明细拒绝由 SDK 既有测试覆盖，未经网关与 demo-service 真链路。platform-console 单测 12 项（新增套餐面板 2 项、更换套餐 1 项）、全部前端 typecheck 与 format:check 通过。事件按消费端方式直接调用处理器，未经真实 RocketMQ。
- 已知限制：存储容量只存储与广播，配额校验在工单 24 实现；新租户的 `TenantCreated` 与 `TenantPlanChanged` 分别送达，iam 收到后者前该租户不受套餐限制（此时 Owner 尚未接受邀请）；platform 的 Operator 功能权限仍待工单 22；修改一个被大量租户使用的 Plan 会在同一事务中为每个租户写一条 Outbox 事件。
