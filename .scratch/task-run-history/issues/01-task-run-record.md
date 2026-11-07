# 01: 运行记录、失败租户与清理

**What to build:** 按 spec 第 2 节，让定时触发的任务产生 Task Run。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] Global Task 与 Tenant Task 都产生运行记录，Tenant Task 记录成功数、失败数和失败租户
- [ ] 抢锁跳过不产生运行记录，原有指标不变
- [ ] 记录写入失败时任务照常执行
- [ ] 下次抢到锁时，把同名任务遗留的运行中记录标记为已中断
- [ ] 已结束记录 90 天后清理
- [ ] 更新 `forge-sdk-scheduling` 的 `package-info` 与接入指南，迁移覆盖所有接入服务与脚手架

## Comments
