# 02: forge-sdk-scheduling 与 Global Task

**What to build:** 按 [spec](../spec.md) 第 2、3.1、4 节，新 SDK 模块 `forge-sdk-scheduling` 提供 `@GlobalTask`（cron 或固定间隔）、Redis 互斥锁与不重叠执行、日志与指标。

**Blocked by:** reference-data/04（`forge.business-zone`）

**Status:** ready-for-agent

- [ ] `@GlobalTask(name, cron | fixedDelay)`：`name` 必填且服务内唯一（重复时启动失败），`cron` 与 `fixedDelay` 二选一，`fixedDelay` 支持占位符
- [ ] cron 按 `forge.business-zone` 解释；服务存在 cron 任务而漏配时启动失败，只有固定间隔时不要求
- [ ] Redis 锁：`SET NX PX` + 持有者令牌，租期参考 30 秒、每 10 秒续期，释放时校验令牌
- [ ] 抢不到锁或 Redis 不可用时跳过本次触发，分别计入跳过与抢锁失败指标；不补跑
- [ ] 指标与日志：执行次数、耗时、跳过、抢锁失败，标签含 `name`
- [ ] 模块依赖 `forge-sdk-tenant` 与 Redis，`forge-architecture` 的 SDK 依赖与循环规则通过
- [ ] 测试覆盖：两个实例只有一个执行、上一轮未结束时下一次被跳过、续期使长任务不丢锁、令牌不符不能释放

## Comments
