# 定时任务运行记录规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认；运行记录的存储方案其后由数据库改为 Redis。

## 1. 背景与目标

`@GlobalTask` 与 `@TenantTask` 目前只有指标和日志，失败的租户也不补跑，Operator 无法确认任务是否执行过，也无法手动重跑。

目标：记录最近的 Task Run，支持 Operator 手动触发和单租户重跑。运维通道见 [ADR-0021](../../docs/adr/0021-operator-ops-via-service-internal-endpoints.md)。

非目标：

- 界面修改 cron、新建或停用任务；Tenant Admin 查看任务；失败自动重试；记录抢锁跳过的触发（仍只记指标）。
- 长期留存、合规证据、按月统计成功率、Redis 数据丢失后恢复历史。如果以后需要这些能力，再单独设计。

## 2. Task Run

### 2.1 为什么存 Redis

- 调度已经依赖 Redis 抢锁：Redis 不可用时任务本来就会跳过，存 Redis 不增加新的失败来源。
- 数据库故障时任务的运行记录仍可写入，而这正是最需要记录的时候。
- 高频任务（如每 30 秒一次的 `forge-io-interrupted`）不会给业务库带来持续写入，接入调度的服务也不用增加迁移。
- 代价是只有最近的历史。手动操作的追溯由平台审计负责，所以这部分数据丢失不影响问责。

### 2.2 记录内容

- 运行 ID、任务名、类型（全局/按租户）、触发方式（定时/手动）、触发的 Operator、目标租户、状态、开始和结束时间、租户成功与失败数、错误摘要。
- 状态：运行中、成功、部分失败、失败、已中断。
- **错误摘要只保留异常类型和 `ForgeException` 错误码，不保留异常 message**，因为其中可能包含业务数据；完整异常留在服务日志。原则与 `DeadEventErrorSummary` 一致。
- 失败租户：每次运行最多记录 100 个（租户 ID 和错误摘要），并始终保留失败总数；超出的部分查日志，或重跑整个任务。成功的租户只计数。

### 2.3 Redis 结构

键前缀为 `forge:task-run:<service>:<task>:`，与锁键 `forge:task:<service>:<task>` 分开，并沿用锁使用的全局 `StringRedisTemplate`。

| 键 | 内容 | 上限 |
|---|---|---|
| `current` | 正在运行的记录（含锁令牌） | 1 条 |
| `recent` | 最近结束的运行 | 100 条 |
| `problems` | 最近非成功的运行（部分失败、失败、已中断） | 100 条 |

- `problems` 单独保存，失败记录才不会被高频任务的成功记录挤掉。
- 列表的写入和裁剪必须原子完成。每次写入把这些键的 TTL 刷新为 90 天，这样改名或删除的任务不会永久留下数据。
- 这些数据只含租户 ID 和错误摘要，不含业务数据，不接入租户清理流程，由上限和 TTL 自然淘汰。
- 生产 Redis 的淘汰策略不得淘汰带 TTL 的键，这与现有任务锁的要求相同。

### 2.4 记录时机与异常

- 抢到锁后写 `current`，结束后写入列表，并只删除属于本次运行的 `current`。
- 运行记录读写失败只记 WARN，不影响任务执行和锁的释放；记录也不参与任何任务决策。
- **崩溃后的状态：** 读取时，如果 `current` 中的令牌与当前持锁者不一致，就显示为已中断。下次抢到锁时，把遗留的 `current` 以已中断状态归档到列表。

## 3. 手动触发

- 异步执行，立即返回运行 ID；走同一把 Redis 锁，已在运行时拒绝（`task.alreadyRunning`）。
- Tenant Task 可指定单个租户重跑；租户不是试用或正常状态时拒绝（`task.tenantNotEligible`）。Global Task 不接受租户参数。
- 平台记录审计：`ops.taskTrigger`、`ops.taskTenantRetry`。
- 业务任务本来就必须幂等，手动触发不增加新的幂等要求。
- 接入方式与 Dead Event 相同：`forge-sdk-ops` 存在时才注册内部接口，只用调度能力的服务不受影响。

## 4. 查询

平台按服务列出已声明的任务及最近一次运行，可查看最近运行、最近非成功运行和失败租户。界面须说明这里只保留最近的记录，并在失败租户超过 100 个时显示未列出的数量。

## 通用验收

- 后端用集成测试覆盖，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`。
- 不新增数据库表，接入调度的服务不需要新迁移。
- 同步更新 `forge-sdk-scheduling` 的 `package-info`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
