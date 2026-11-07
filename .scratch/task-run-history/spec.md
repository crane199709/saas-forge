# 定时任务运行记录规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

`@GlobalTask` 与 `@TenantTask` 目前只有指标和日志，失败的租户也不补跑，Operator 无法确认任务是否执行过，也无法手动重跑。

目标：记录 Task Run，支持 Operator 手动触发和单租户重跑。运维通道见 [ADR-0021](../../docs/adr/0021-operator-ops-via-service-internal-endpoints.md)。

非目标：界面修改 cron、新建或停用任务；Tenant Admin 查看任务；失败自动重试；记录抢锁跳过的触发（仍只记指标）。

## 2. Task Run

- `forge-sdk-scheduling` 从此依赖数据库，这是有意的破坏性变更，接入调度的服务都要复制迁移。
- 运行记录：任务名、类型（全局/按租户）、触发方式（定时/手动）、触发的 Operator、目标租户、状态、开始和结束时间、租户成功与失败数、错误摘要。
- 失败租户单独成表，每行记录租户和截断后的错误；成功的租户只计数。
- 两张表不带 RLS、不用标准列，已结束的记录保留 90 天后清理。
- 运行记录写入失败只记 WARN，不阻止任务执行。
- 进程崩溃会留下“运行中”的记录：同名任务下次抢到锁时，把旧的运行中记录标记为已中断（持锁即说明它们已不在运行）。

## 3. 手动触发

- 异步执行，立即返回运行 ID；走同一把 Redis 锁，已在运行时拒绝（`task.alreadyRunning`）。
- Tenant Task 可指定单个租户重跑；租户不是试用或正常状态时拒绝（`task.tenantNotEligible`）。Global Task 不接受租户参数。
- 平台记录审计：`ops.taskTrigger`、`ops.taskTenantRetry`。
- 业务任务本来就必须幂等，手动触发不增加新的幂等要求。

## 4. 查询

平台按服务列出已声明的任务及最近一次运行，可查看运行历史和失败租户。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。
