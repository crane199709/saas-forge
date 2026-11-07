# 02: Dead Event 落库与指标

**What to build:** 按 spec 第 3 节，让重试耗尽的事件写入 `forge_dead_event`，并增加指标。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 达到上限前继续返回 `FAILURE`，达到上限后独立事务写入并返回 `SUCCESS`
- [ ] Dead Event 写入失败时返回 `FAILURE`，事件不丢
- [ ] 重复写入同一事件幂等，解析失败也能进入死信
- [ ] 验证本地部署的消费组重试次数不小于上限，必要时调整 `deploy/rocketmq`
- [ ] 增加 Dead Event 与 Outbox 积压指标
- [ ] 迁移覆盖 iam、platform、demo-service 与脚手架

## Comments
