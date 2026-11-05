# 05: demo-service 自动关闭超时订单

**What to build:** 按 [spec](../spec.md) 第 6 节，demo-service 新增 Tenant Task「自动关闭超时未支付订单」，超时时长读取参数 `order.auto-close`，作为端到端验收。

**Blocked by:** 03, reference-data/03

**Status:** ready-for-agent

- [ ] `@TenantTask(name = "order.auto-close", cron = ...)` 关闭超过时长的未支付订单，条件更新保证幂等
- [ ] 不同租户覆盖不同的 `order.auto-close` 时按各自的值关闭
- [ ] 测试覆盖：按租户参数生效、某租户失败不影响其他租户、重复执行无副作用

## Comments
