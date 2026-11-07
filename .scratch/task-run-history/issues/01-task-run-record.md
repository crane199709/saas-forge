# 01: Redis 运行记录与失败租户

**What to build:** 按 spec 第 2 节，让定时触发的任务把 Task Run 写入 Redis。

**Blocked by:** —

**Status:** resolved

- [x] Global Task 与 Tenant Task 都产生运行记录，Tenant Task 记录成功数、失败数和失败租户
- [x] 抢锁跳过不产生运行记录，原有指标不变
- [x] `recent` 和 `problems` 各最多 100 条，写入与裁剪原子完成，每次写入刷新 90 天 TTL
- [x] 大量成功运行不会挤掉 `problems` 中的非成功记录
- [x] 失败租户最多记录 100 个，失败总数保持准确
- [x] 错误摘要不含异常 message，测试验证 message 中的敏感值不会写入 Redis
- [x] 运行记录读写失败时任务照常执行，锁正常释放，并记录 WARN
- [x] 进程崩溃后，读取时显示已中断；下次抢到锁时把遗留记录归档为已中断
- [x] 结束时只删除属于本次运行的 `current`，失去锁的旧运行不会清掉新运行的记录
- [x] 不新增数据库表和迁移，更新 `package-info` 与接入指南

## Comments

- 2026-11-07：由 codex/task-run-record 认领；基于集成分支 a57188e，测试入口为 SDK 调度与运行查询公开接口的真实 Redis 集成测试。

## Answer

已实现：`TaskRunHistory` Spring Bean 提供 `current(name)`、`recent(name)`、`problems(name)`；返回的 `TaskRun` 及嵌套枚举/失败租户值对象不暴露锁令牌。调度执行累计全局与租户结果，安全摘要排除 message 与异常参数，失败明细最多 100 个而总数准确。

Redis 使用独立 `forge:task-run:<service>:<task>:` 键族；Lua 原子归档、裁剪至 100 条并刷新已有键的 90 天 TTL。当前运行与锁在同一快照中比较令牌，失锁显示中断，下次持锁归档遗留运行；旧执行结束不会删除新持有者的记录。记录读写故障只 WARN，不影响任务与锁。

新增 9 项真实 Redis 运行记录测试，连同受影响的原有调度测试共 45 项通过。已同步 package-info、接入指南和 CHANGELOG；未添加数据库迁移。红绿证据、命令与验收边界见 [工单 01 验收记录](../validation-01.md)。模块全量交由集成分支统一执行。
