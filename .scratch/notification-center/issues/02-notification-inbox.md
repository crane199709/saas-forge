# 02: Notification 分区表与收件箱接口

**What to build:** 按 spec 第 3 节，由 `forge-iam` 消费事件并提供收件箱接口。

**Blocked by:** 01

**Status:** resolved

- [x] 按月分区并自动维护分区，保留 180 天后删除分区
- [x] 按 Permission Code 在创建时展开，忽略不属于该 Tenant 的 Membership
- [x] 支持分页、标记已读、全部已读和未读数
- [x] 重复投递不产生重复通知
- [x] 两个 Tenant 的通知互相隔离，已离开的 Membership 不能访问
- [x] demo-service 发送一条真实通知完成端到端验证

## Comments

- 2026-11-07：codex/notification-inbox 认领；TDD 边界已确认，真实 MQ 联调交票05。

## Answer

已实现 Notification 事件幂等收件快照、Permission 创建时展开、active Membership收件箱分页/已读/全读/计数、180天整月分区维护及按租户最终清理。Demo订单创建经真实NotificationClient发送，链接demo:/overview；网关动作POST /demo/orders。接口契约与红绿证据见[validation-02.md](../validation-02.md)。真实MQ跨服务及浏览器最终验收按分工由票05完成，本票不宣称已验证Broker。


## 验收修复

2026-11-07：补齐Notification父表、自动月分区的强制RLS与可信租户事务绑定；既有HTTP契约不变。红绿门禁、实际iam_app直接分区探针、回滚切租户及并发首建记录见[validation-rls.md](../validation-rls.md)。
