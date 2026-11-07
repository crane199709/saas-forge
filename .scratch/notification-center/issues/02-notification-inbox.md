# 02: Notification 分区表与收件箱接口

**What to build:** 按 spec 第 3 节，由 `forge-iam` 消费事件并提供收件箱接口。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 按月分区并自动维护分区，保留 180 天后删除分区
- [ ] 按 Permission Code 在创建时展开，忽略不属于该 Tenant 的 Membership
- [ ] 支持分页、标记已读、全部已读和未读数
- [ ] 重复投递不产生重复通知
- [ ] 两个 Tenant 的通知互相隔离，已离开的 Membership 不能访问
- [ ] demo-service 发送一条真实通知完成端到端验证

## Comments
