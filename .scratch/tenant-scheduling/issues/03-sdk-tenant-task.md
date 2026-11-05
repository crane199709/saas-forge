# 03: Tenant Task

**What to build:** 按 [spec](../spec.md) 3.2–3.3 节，`@TenantTask` 在抢到锁后从 iam 拉取试用与正常租户，逐个在 `CurrentTenant.run` 中执行，失败隔离。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] `@TenantTask(name, cron | fixedDelay)`，互斥、跳过、指标规则同 Global Task
- [ ] 每次执行分页拉取 `/internal/tenants?status=trial,active`
- [ ] 单线程逐租户顺序执行；执行前用 `TenantStatusLookup` 复查，已非试用或正常的跳过，不计失败
- [ ] 单个租户异常：记日志（带租户 ID）、计入失败指标、继续下一个；不在本轮重试
- [ ] 以系统身份运行，审计列操作人为 NULL；不绑定 Membership
- [ ] 增加租户成功与失败数指标；SDK 文档写明任务必须幂等
- [ ] 测试覆盖：失败租户不影响其他租户、停用与过期租户不执行、执行中途被停用的租户被跳过、未在上下文外访问租户数据

## Comments
