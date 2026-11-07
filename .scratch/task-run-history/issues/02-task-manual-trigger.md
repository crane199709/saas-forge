# 02: 手动触发、单租户重跑与查询接口

**What to build:** 按 spec 第 3、4 节，提供 SDK 运维接口和平台接口。

**Blocked by:** 01, mq-dead-event/01

**Status:** ready-for-agent

- [ ] 手动触发立即返回运行 ID 并异步执行，运行记录标明触发方式和 Operator
- [ ] 任务已在运行时返回 `task.alreadyRunning`
- [ ] 单租户重跑只执行指定租户，并绑定该租户上下文
- [ ] 租户状态不符时返回 `task.tenantNotEligible`，Global Task 拒绝租户参数
- [ ] 提供任务列表、最近运行、最近非成功运行和失败租户的查询接口，错误内容只含摘要
- [ ] 平台记录 `ops.taskTrigger`、`ops.taskTenantRetry`，并校验 `platform:ops:read`、`platform:ops:manage`
- [ ] 没有引入 `forge-sdk-ops` 的服务不注册内部接口，调度功能不受影响

## Comments
