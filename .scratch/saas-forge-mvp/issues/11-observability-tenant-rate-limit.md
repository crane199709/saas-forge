# 11: 可观测性与按租户限流

**What to build:** 任一服务的日志自动带上 `tenant_id`、`membership_id`、`traceId`，一次跨服务请求能用同一 traceId 串起来；指标可导出。网关按租户限流，第一期对所有租户使用全局默认阈值。第一期不搭建采集后端。

**Blocked by:** 03 Tenant Realm 最小登录链路

**Status:** ready-for-agent

- [x] SDK 自动把租户、成员与 trace 信息写入日志 MDC，无需业务代码参与
- [x] Micrometer Tracing + OpenTelemetry 导出配置就绪，网关到下游服务的 trace 连续
- [x] Micrometer 指标可导出
- [x] 网关使用 Sentinel 按 `tenant_id` 限流，超限返回明确的限流响应
- [x] 默认阈值可配置，并预留按套餐配置阈值的扩展点（不实现）
- [x] 测试：单租户超限被限流时不影响其他租户

## Comments

2026-10-26 与统一响应格式的分工（见 [unified-api-envelope 规格](../../unified-api-envelope/spec.md) 第 6、8 节）：
- 已移交 unified-api-envelope 工单 02、03：gateway 与 Servlet 服务引入 Micrometer Tracing OTel bridge，以 W3C `traceparent` 传播使网关到下游 trace 连续；日志 pattern 输出 traceId；gateway 回写响应头 `X-Trace-Id`，与响应信封中的 `traceId` 一致。
- 本工单保留：MDC 写入 `tenant_id`、`membership_id`；OTel exporter 配置；Micrometer 指标导出；Sentinel 按租户限流与阈值扩展点。
- 限流响应使用统一信封：HTTP 429，`code` 为 `request.rateLimited`，`userTip` 走 i18n 文案，带 `traceId`；可附 `Retry-After` 响应头。

2026-10-27 实现说明：
- MDC：`CurrentTenant.bind` 写入 `tenant_id`（HTTP 请求、MQ 消费、`CurrentTenant.run/wrap` 的异步与定时任务都覆盖），`CurrentTenantFilter` 写入 `membership_id`（只有 HTTP 请求有成员身份）。forge-sdk-web 默认日志关联段为 `[traceId-spanId] [tenant_id,membership_id] `，无租户时省略后一段；服务自定义 `logging.pattern.console/file` 时需自行保留 `LOG_CORRELATION_PATTERN`。
- 网关日志只带 traceId，不带 `tenant_id`/`membership_id`：响应式链路跨线程，需要另做 Reactor context 到 MDC 的传播，留作后续。
- 导出：引入 `spring-boot-starter-opentelemetry`。trace 在配置 `management.opentelemetry.tracing.export.otlp.endpoint` 后导出；Micrometer 指标经 OTLP 推送，SDK 与网关默认关闭，以 `management.otlp.metrics.export.enabled=true` 与 `url` 开启。
- 限流：网关 `TenantRateLimitFilter` 以 Sentinel 热点参数规则按 `tenant_id` 计数，阈值 `forge.gateway.rate-limit.default-qps`（默认 100），按单实例计数、启动时加载。按套餐阈值的扩展点为 `TenantQpsOverrides`，未实现。匿名接口按 IP 限流不在本工单。
