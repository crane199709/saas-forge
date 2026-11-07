# 03: 重放、放弃与清理

**What to build:** 按 spec 第 4 节，提供 SDK 运维接口与平台接口，并按保留期清理已处置记录。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] 重放恢复租户上下文，成功后事件只生效一次；失败时保持待处置并更新错误
- [ ] 重放不向 MQ 发消息，其他消费组不会再次收到
- [ ] 放弃必须填写原因，已处置事件不能再次处置，并发处置只有一次成功
- [ ] 平台接口记录 `ops.deadEventReplay`、`ops.deadEventAbandon`
- [ ] 接口响应不含 payload
- [ ] 已处置 90 天后清理，待处置记录不被清理

## Comments
