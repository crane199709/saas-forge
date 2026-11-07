# 01: Redis 运行记录与失败租户

**What to build:** 按 spec 第 2 节，让定时触发的任务把 Task Run 写入 Redis。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] Global Task 与 Tenant Task 都产生运行记录，Tenant Task 记录成功数、失败数和失败租户
- [ ] 抢锁跳过不产生运行记录，原有指标不变
- [ ] `recent` 和 `problems` 各最多 100 条，写入与裁剪原子完成，每次写入刷新 90 天 TTL
- [ ] 大量成功运行不会挤掉 `problems` 中的非成功记录
- [ ] 失败租户最多记录 100 个，失败总数保持准确
- [ ] 错误摘要不含异常 message，测试验证 message 中的敏感值不会写入 Redis
- [ ] 运行记录读写失败时任务照常执行，锁正常释放，并记录 WARN
- [ ] 进程崩溃后，读取时显示已中断；下次抢到锁时把遗留记录归档为已中断
- [ ] 结束时只删除属于本次运行的 `current`，失去锁的旧运行不会清掉新运行的记录
- [ ] 不新增数据库表和迁移，更新 `package-info` 与接入指南

## Comments
