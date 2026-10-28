# 15: 租户生命周期

**What to build:** Operator 在 platform-console 按状态机推进 Tenant Lifecycle（待开通、试用、正常、停用、过期、归档、删除）。每次变更广播到各服务：停用或过期后租户成员访问被网关拒绝、SDK 拒绝写入；删除进入 Cooling-off Period（默认 30 天，可配置），期内可撤回，到期后广播 `TenantDeleted`，各服务把本服务该租户的数据标记删除（ADR-0016）。到期提醒在试用或订购到期前触发。

**Blocked by:** 07 Token 即时失效与刷新, 14 租户创建与开通

**Status:** ready-for-agent

- [x] 状态机以显式的合法迁移表定义，非法迁移被拒绝
- [x] 每次状态变更经 Outbox 广播 `TenantStatusChanged`，并更新网关可读取的租户状态
- [x] 停用、过期租户的成员请求被拒（即时生效）
- [x] SDK 提供按租户状态拒绝写入的默认行为，服务可声明例外
- [x] 删除进入冷静期，期内可撤回；到期后广播 `TenantDeleted`，demo-service 把本服务该租户数据标记删除
- [x] 各服务的租户清理是批量逻辑删除：在 `CurrentTenant.run(tenantId, ...)` 中执行 mapper XML 里的 `UPDATE ... SET deleted = true, updated_at = now()`，`updated_by` 保持 NULL（建表标准列规格第 6 节），在本工单实现
- [x] 到期提醒经邮件/短信 SPI 发送给 Owner
- [x] platform-console 可执行状态迁移并查看变更历史
- [x] 测试：非法迁移被拒；停用后成员下一次请求被拒；冷静期结束后 demo-service 该租户数据被标记删除

## Comments

2026-10-28 实现：
- 状态机：platform `TenantLifecycleService.TRANSITIONS` 为显式迁移表（pending → trial/active/deleted；trial → active/suspended/expired/deleted；active → suspended/expired/deleted；suspended → trial/active/archived/deleted；expired → active/archived/deleted；archived → deleted）。deleted 不在表中：冷静期内只能迁移回删除前的状态（即撤回），冷静期结束后不能再迁移。非法迁移 409 `tenant.illegalTransition`；`status_version` 作乐观锁，并发变更 409 `tenant.statusConflict`。接口 `POST /tenants/{id}/transitions`（`status`、`reason`、可选 `expiresAt`，只在迁移到 trial/active 时可填）；详情返回 `allowedTransitions` 与 `statusChanges`（新表 `tenant_status_change`，创建也记一条）。创建请求可选 `status`（trial/active，默认 active）与 `expiresAt`，TODO(#20) 由 Plan 决定。
- 广播：状态变更、历史与 `TenantStatusChanged(tenantId, name, previousStatus, status, version)` 同一事务经 Outbox 发布（Topic `forge-tenant`，带该租户上下文）。iam 消费后按 `version` 只应用更新的变更（upsert，未登记的租户直接登记），在消费事务内先改库再写 Redis `iam:{tenant}:status`，Redis 失败则消息重投。`TenantCreated` 增加 `status` 字段，iam 按此登记初始状态。原 `TenantStateService.changeStatus` 由 `apply` 取代。
- 访问：`MembershipStateKeys.accessible` 只放行 trial 与 active；网关、iam 登录/刷新/切换（SQL 改为 `t.status IN ('trial', 'active')`）与签发时补写的状态（改为数据库中的实际状态，不再固定写 active）一致。停用、过期等状态在成员下一次请求即 401。
- SDK 写入保护：forge-sdk-tenant 在服务使用 Redis 时注册 MyBatis-Plus 拦截器，当前租户状态为 suspended/expired/archived/deleted 时拒绝 INSERT/UPDATE/DELETE，抛 403 `tenant.writeBlocked`（新增框架错误码；forge-sdk-web 从 MyBatis 包装的异常中取出原因映射）。全局表、`@InterceptorIgnore(tenantLine)` 的 Mapper（Outbox 等基础设施、iam 全部表）与无租户上下文的写入不受限。例外用 `TenantWriteGuard.exempt(Runnable)` 声明；可用 `forge.tenant.write-guard.enabled=false` 整体关闭。状态读取 iam 发布的同一 Redis 键，而非各服务订阅广播：同一服务多个实例共用消费组，广播只会到达其中一个实例。键缺失时放行（网关此时已拒绝成员请求，拒绝会让 Redis 淘汰后开通等系统写入全部失败），Redis 读取失败时写入失败。每条租户表写入多一次 Redis GET，未加本地缓存。
- 冷静期：迁移到 deleted 时记录删除前状态与 `purge_at = now + forge.platform.lifecycle.cooling-off-period`（默认 30d）。platform 定时任务（`scan-interval` 默认 1m）对到期租户条件更新 `purged_at` 后发布 `TenantDeleted`，每个租户只发布一次，与并发撤回经状态版本互斥。SDK 新增扩展点 `TenantDataCleaner`：服务声明后 SDK 订阅 `TenantDeleted`，校验消息租户与事件一致，在 `CurrentTenant.run(tenantId, ...)` 与写入保护例外中调用；失败则回滚并由 RocketMQ 重投。demo-service 的订单、订单通知、订单分类各自在 mapper XML 中 `UPDATE ... SET deleted = true, updated_at = now(), updated_by = NULL WHERE NOT deleted`。
- 到期提醒：同一定时任务对 trial/active 且 `expires_at` 落在 `reminder-lead-time`（默认 7d）内、尚未提醒的租户条件更新 `expiry_reminded_at` 后发布 `TenantExpiryApproached`；迁移时设置新的 `expiresAt` 会重置提醒。iam 消费后查出 Owner（active 或 invited 的 Membership，邮箱优先）经 `ContactSender` 发送新的 `ExpiryReminderMessage`（`ContactMessage` 新增一种，部署方的 `EmailSender`/`SmsSender` 需支持该模板）。
- 前端：platform-console 租户详情展示状态、到期时间、冷静期结束/清理开始时间，按 `allowedTransitions` 选择目标状态（删除时提示冷静期，冷静期内显示为「撤回删除」）、填写原因与到期时间，并展示变更历史；创建对话框可选初始状态与到期时间。
- 验证：platform `TenantLifecycleTest` 3 项（非法迁移与到期时间被拒、合法迁移广播并记历史、冷静期内撤回、期满只发布一次 `TenantDeleted` 且不能再撤回、到期提醒每个到期时间只发一次）；iam `TenantLifecycleTest` 3 项（试用租户可进入、停用后不再可进入、旧版本不覆盖新状态、未登记租户的停用生效、到期提醒发给尚未接受邀请的 Owner）；网关新增「只有 trial 与 active 可访问」；demo-service `TenantLifecycleTest` 2 项（停用/过期/归档/删除时接口与无请求上下文的写入被拒、查询不受影响、例外可写；`TenantDeleted` 后该租户三张表标记删除且 `updated_by` 为 NULL、重复投递安全、其他租户不受影响、租户上下文不一致时拒绝）。事件均按消费端方式直接调用处理器，未经真实 RocketMQ 与三服务真链路；「停用后成员下一次请求被拒」由 iam（事件 → Redis）与网关（Redis → 401）分段验证。
- 已知限制：到期不会自动迁移到 expired，由 Operator 操作；iam 未实现 `TenantDataCleaner`（租户处于 deleted 状态已不能登录与访问，iam 数据清理留待后续）；Operator 功能权限仍待工单 22。

2026-10-28 后续待定（暂不改）：
- 到期自动迁移：`expires_at` 只用于到期前提醒，到期后状态不变，成员照常访问与写入，须 Operator 手动迁移到 expired。自动过期需先确定宽限期、试用到期进入 expired 还是 suspended、是否通知 Owner，到期时间来源随 Plan（工单 20）确定。实现方式：在同一扫描中对 `status IN (trial, active) AND expires_at <= now` 条件更新为 expired，历史操作人为 NULL，广播 `TenantStatusChanged`。
- 调度方式：冷静期清理与到期提醒目前由 forge-platform 每个实例按 `scan-interval` 各自扫描，靠每个租户的条件更新保证只有一个实例发布事件（未经多实例部署验证）；代价是多实例重复扫描，且到期判断用各实例本机时钟。已决定先保持现状（不引入 ShedLock 等锁），后续定时任务增多（审计分区、配额统计、Outbox 与刷新凭证清理等）时再考虑引入 XXL-Job 统一调度并记 ADR；无论用什么调度，条件更新这一层幂等都保留，因为调度平台的故障转移与重试同样可能重复执行。
