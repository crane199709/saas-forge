# 02: 手动触发、单租户重跑与查询接口

**What to build:** 按 spec 第 3、4 节，提供 SDK 运维接口和平台接口。

**Blocked by:** 01, mq-dead-event/01

**Status:** resolved

- [x] 手动触发立即返回运行 ID 并异步执行，运行记录标明触发方式和 Operator
- [x] 任务已在运行时返回 `task.alreadyRunning`
- [x] 单租户重跑只执行指定租户，并绑定该租户上下文
- [x] 租户状态不符时返回 `task.tenantNotEligible`，Global Task 拒绝租户参数
- [x] 提供任务列表、最近运行、最近非成功运行和失败租户的查询接口，错误内容只含摘要
- [x] 平台记录 `ops.taskTrigger`、`ops.taskTenantRetry`，并校验 `platform:ops:read`、`platform:ops:manage`
- [x] 没有引入 `forge-sdk-ops` 的服务不注册内部接口，调度功能不受影响

## Comments

## Answer

已提供 SDK `/internal/ops/tasks` 与平台 `/ops/services/{service}/tasks` 查询及触发接口。手动请求同步抢锁、生成并返回运行 ID 后异步执行；目标租户同步校验 trial/active，执行时绑定该 Tenant。任务声明包含解析后的规则和优先 current 的最近运行，查询支持 recent/problems 和失败 Tenant；平台使用可信 Operator、现有读/管理权限与独立令牌，并记录成功/失败审计。未引入 ops 模块时不注册内部路由，普通调度保持可用。

[验收证据与工单 03 API 契约](../validation-02.md)：SDK 目标及架构 53 项、平台目标及架构 16 项通过，后续受影响 HTTP 复验也通过；Spotless 与 diff 检查通过。已同步 package-info、接入指南和 CHANGELOG。模块全量与 Playwright 交由根集成流程及工单 03。
