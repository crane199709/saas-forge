# 03: 安全与网关错误输出

**What to build:** 未认证、无权限、Token 失效、下游不可用等由 Spring Security 或 gateway 直接产生的错误，同样返回统一信封，而不是空响应体或 WebFlux 默认格式。gateway 生成 trace 并在响应头回写 `X-Trace-Id`，下游服务继承同一 trace。

**Blocked by:** 02 信封模型与 forge-sdk-web

**Status:** ready-for-agent

- [x] `forge-sdk-security` 替换默认 `AuthenticationEntryPoint` 与 `AccessDeniedHandler`：401 输出 `auth.unauthenticated`，403 输出 `auth.forbidden`，带 `userTip` 与 `traceId`
- [x] gateway 的安全处理器同样输出信封：缺失、伪造、issuer 不符、过期 Token，以及 Membership `ver` 或租户状态不一致，均为 401 `auth.unauthenticated`
- [x] gateway 的 `ErrorWebExceptionHandler` 覆盖：路由不存在 404 `request.notFound`；下游不可用或超时、Redis 不可用 503 `system.unavailable`；其他异常 500 `system.error`（不含异常信息）
- [x] gateway 透传下游服务的信封响应，不重复包装
- [x] gateway 引入 Micrometer Tracing OTel bridge，以 W3C `traceparent` 向下游传播；所有经过 gateway 的响应带 `X-Trace-Id`，与信封 `traceId` 相同
- [x] 不影响 `/oauth2/*`、OIDC、JWKS 与 actuator 的原有格式；gateway 关闭 `/iam/oauth2/token` 的现有行为保持不变
- [x] 测试：在现有 `GatewaySecurityTest` 的状态码断言基础上，增加对信封 `code`、`traceId` 与 `X-Trace-Id` 的断言；503 与路由 404 有覆盖；服务侧 401、403 信封有覆盖；一次经 gateway 的请求，下游日志中的 traceId 与响应头一致

## Comments

2026-10-26 实现：`forge-sdk-security` 新增 `EnvelopeAuthenticationEntryPoint`、`EnvelopeAccessDeniedHandler`，作为 Bean 暴露；默认过滤链把它们同时配到 `exceptionHandling` 与 `oauth2ResourceServer`。处理时先委托 Bearer 默认处理器保留 `WWW-Authenticate`，再写信封；文案直接取 `FrameworkMessages`，traceId 取 Micrometer `Tracer`，模块只新增 `micrometer-tracing` API 依赖。方法安全抛出的 `AccessDeniedException` / `AuthenticationException` 由最高优先级的 `SecurityExceptionPassthroughAdvice` 原样抛回过滤链交给信封处理器，避开 forge-sdk-web 的兜底 500；forge-sdk-security 仍不依赖 forge-sdk-web（仅测试依赖）。gateway 新增功能包 `envelope`：`ErrorEnvelopeService` 写信封并从 WebFlux 观测上下文取 traceId；`TraceIdResponseFilter` 在提交响应前写 `X-Trace-Id`；`EnvelopeErrorWebExceptionHandler`（order -2）把 404、405、其他 4xx 分别映射为对应信封，502/503/504 及连接、超时、Redis 故障映射为 503，其余为 500。安全配置的入口与拒绝处理器输出 401/403 信封。引入 `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel` 后，SCG 自带的观测即向下游传播 `traceparent`。
- 偏离与约定：网关 401/403 不再带 `WWW-Authenticate`，因为 Bearer 默认入口会直接提交响应，之后无法再写信封；`EnvelopeErrorWebExceptionHandler` 放在 `filter` 层（WebFlux 管线组件，白名单里没有更贴切的层名）；下游超时（504）也映射为 503 `system.unavailable`。「下游日志 traceId 与响应头一致」改为断言下游收到的 `traceparent` trace id 等于 `X-Trace-Id`；下游按 `traceparent` 记录 traceId 已由工单 02 的 forge-sdk-web 测试覆盖。
- 验证：`SecurityErrorEnvelopeTest` 4 项（缺失/无效 Token 401、方法安全 403 与 forge-sdk-web 共存、认证通过）；`GatewaySecurityTest` 13 项（原状态码断言加 `code`、`traceId` 与 `X-Trace-Id`，新增 traceparent 传播、下游信封透传、下游不可用与超时 503、未知异常 500 不泄漏、路由 404）；`RedisUnavailableTest` 断言 503 `system.unavailable` 信封。`./mvnw -pl forge-sdk/forge-sdk-security,forge-gateway -am verify` 与完整 `./mvnw verify` 通过。
