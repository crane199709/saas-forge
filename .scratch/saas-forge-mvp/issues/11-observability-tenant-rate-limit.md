# 11: 可观测性与按租户限流

**What to build:** 任一服务的日志自动带上 `tenant_id`、`membership_id`、`traceId`，一次跨服务请求能用同一 traceId 串起来；指标可导出。网关按租户限流，第一期对所有租户使用全局默认阈值。第一期不搭建采集后端。

**Blocked by:** 03 Tenant Realm 最小登录链路

**Status:** ready-for-agent

- [ ] SDK 自动把租户、成员与 trace 信息写入日志 MDC，无需业务代码参与
- [ ] Micrometer Tracing + OpenTelemetry 导出配置就绪，网关到下游服务的 trace 连续
- [ ] Micrometer 指标可导出
- [ ] 网关使用 Sentinel 按 `tenant_id` 限流，超限返回明确的限流响应
- [ ] 默认阈值可配置，并预留按套餐配置阈值的扩展点（不实现）
- [ ] 测试：单租户超限被限流时不影响其他租户
