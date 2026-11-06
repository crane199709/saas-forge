# 租户感知的定时任务规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准（「定时任务」一节）。本规格的决策经 2026-11-04 的设计拷问逐条确认（Q42–Q56），未单独记录 ADR。

## 1. 背景与目标

- `CurrentTenant` 要求定时任务显式声明租户，否则访问租户数据时失败；但 SDK 不提供「有哪些租户」，业务表又启用 `FORCE ROW LEVEL SECURITY`（ADR-0001），应用账号无法用一条 SQL 跨租户扫描，只能逐租户在上下文中执行。
- 现有唯一的定时任务是 platform 的生命周期扫描（`LifecycleSchedulingConfiguration`，`@EnableScheduling` + 固定间隔），每个实例都扫，靠条件更新保证只发一次事件。

目标：SDK 提供 Global Task 与 Tenant Task 两类声明式任务，多实例互斥，Tenant Task 逐租户在上下文中执行且失败隔离，并统一日志与指标。

非目标：中心化调度（任务注册上报、管理界面、手动触发、执行记录表）；按「任务 + 租户」加锁的多实例并行；失败租户的本轮重试与错过触发的补跑。

## 2. 声明

新 SDK 模块 `forge-sdk-scheduling`，依赖 `forge-sdk-tenant` 与 Redis，不得引入循环依赖。

```java
@TenantTask(name = "order.auto-close", cron = "0 */5 * * * *")
void closeExpiredOrders() { ... }   // 已在某个 Tenant 的上下文中

@GlobalTask(name = "tenant.lifecycle-scan", fixedDelay = "${forge.platform.lifecycle.scan-interval}")
void scanLifecycle() { ... }
```

- 标在 Spring Bean 方法上；`name` 必填、服务内唯一，用作锁键与指标标签，不从方法名推导。
- `cron` 与 `fixedDelay` 二选一，两类任务都支持。
- `cron` 按 Business Time Zone 解释。`forge.business-zone` 由 `forge-sdk-core` 提供（编号共用），代码不设默认值；服务中存在 cron 任务时漏配即启动失败，只用固定间隔的服务不要求。
- 架构规则（`forge-architecture`）：SDK 之外禁止 `@Scheduled` 与 `@EnableScheduling`。

## 3. 执行

### 3.1 互斥与不重叠

- 每次触发先抢该任务的 Redis 锁（`SET NX PX` + 持有者令牌，释放时校验令牌）；租期参考 30 秒，执行期间每 10 秒续期。
- 抢不到锁（其他实例正在执行或上一轮未结束）即跳过本次触发，计入跳过指标，不排队补跑。
- Redis 不可用时同样跳过本轮并计入抢锁失败指标。
- 锁只提升效率、不保证正确性：Redis 主从切换或长时间停顿可能让两个实例短暂同时执行，任务仍须以条件更新等方式自行保证正确。

### 3.2 Tenant Task

- 每次执行开始时从 iam 新增的 `/internal/tenants?status=trial,active` 分页拉取租户 ID；iam 是租户目录的权威来源，不另在 Redis 维护租户集合。
- 由抢到锁的实例在单线程内逐个租户顺序执行，每个租户在 `CurrentTenant.run(tenantId, ...)` 中调用任务方法。
- 执行某个租户前用 `TenantStatusLookup` 复查状态，已不是试用或正常的跳过，不计为失败。
- 某个租户抛出异常时记日志（带租户 ID）、计入失败指标，继续下一个租户；不在本轮重试，下个周期自然再执行。任务必须幂等，SDK 文档写明这一约定。

### 3.3 身份

任务以无操作人的系统身份运行：只绑定租户，不绑定 Membership，审计列 `created_by`、`updated_by` 为 NULL（与 `TenantDataCleaner` 一致）；不提供「以某成员身份执行」。

## 4. 可观测

- 日志：每次执行的开始、结束、耗时；Tenant Task 的失败带租户 ID（MDC 已由 `CurrentTenant` 写入）。
- 指标（Micrometer，标签含任务 `name`）：执行次数与耗时、跳过次数、抢锁失败次数、Tenant Task 的租户成功与失败数。

## 5. 迁移 platform 生命周期扫描

把 `LifecycleSchedulingConfiguration` 改为 `@GlobalTask`（固定间隔，沿用 `scanInterval` 配置），删除 `@EnableScheduling`；保留 `purgeDue`、`remindDue` 中的条件更新作为正确性保证。

## 6. 验收

- demo-service 新增 Tenant Task「自动关闭超时未支付订单」，超时时长读取参考数据中的参数 `order.auto-close`（依赖 [reference-data](../reference-data/spec.md)）。
- platform 生命周期扫描迁移为 Global Task 后，原有生命周期测试通过。
- 两个实例同时运行时同一任务只有一个执行；上一轮未结束时下一次触发被跳过。
- 一个租户抛出异常时其他租户照常执行；停用、过期租户不被执行，执行中途被停用的租户被跳过。
- 存在 cron 任务而漏配 `forge.business-zone` 时启动失败；SDK 之外使用 `@Scheduled` 时架构测试失败。

## 7. 实现与验收记录

五张工单均已完成并关闭，集成分支为 `codex/tenant-scheduling`：

- [01：IAM 内部租户名单](issues/01-iam-internal-tenants.md)
- [02：Global Task 与 Redis 互斥调度](issues/02-sdk-global-task.md)
- [03：Tenant Task 与失败隔离](issues/03-sdk-tenant-task.md)
- [04：架构约束与 platform 生命周期迁移](issues/04-arch-rule-and-platform-migration.md)
- [05：demo 自动关闭超时订单](issues/05-demo-order-auto-close.md)

验证通过：IAM 188 项、网关 38 项、架构规则 53 项、platform 全量 20 项、demo 全量 52 项；全后端 23 模块架构检查通过。审查修正后的最终联合验证通过调度 SDK 37 项、tenant 3 项、platform 生命周期与架构 5 项，相关 Spotless 检查通过；发布版本一致性检查通过。

双轴审查及两项 P3 规范问题的修复见 [审查记录](review.md)，无遗留发现。

部署时须注意：V11 将存量 demo 订单默认设为 `unpaid`，之后按租户的 `order.auto-close` 参数自动关闭。Redis 锁不能替代业务幂等性；租户名单采用现有 offset 分页，不提供跨页快照。
