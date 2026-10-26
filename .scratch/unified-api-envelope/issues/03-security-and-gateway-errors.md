# 03: 安全与网关错误输出

**What to build:** 未认证、无权限、Token 失效、下游不可用等由 Spring Security 或 gateway 直接产生的错误，同样返回统一信封，而不是空响应体或 WebFlux 默认格式。gateway 生成 trace 并在响应头回写 `X-Trace-Id`，下游服务继承同一 trace。

**Blocked by:** 02 信封模型与 forge-sdk-web

**Status:** ready-for-agent

- [ ] `forge-sdk-security` 替换默认 `AuthenticationEntryPoint` 与 `AccessDeniedHandler`：401 输出 `auth.unauthenticated`，403 输出 `auth.forbidden`，带 `userTip` 与 `traceId`
- [ ] gateway 的安全处理器同样输出信封：缺失、伪造、issuer 不符、过期 Token，以及 Membership `ver` 或租户状态不一致，均为 401 `auth.unauthenticated`
- [ ] gateway 的 `ErrorWebExceptionHandler` 覆盖：路由不存在 404 `request.notFound`；下游不可用或超时、Redis 不可用 503 `system.unavailable`；其他异常 500 `system.error`（不含异常信息）
- [ ] gateway 透传下游服务的信封响应，不重复包装
- [ ] gateway 引入 Micrometer Tracing OTel bridge，以 W3C `traceparent` 向下游传播；所有经过 gateway 的响应带 `X-Trace-Id`，与信封 `traceId` 相同
- [ ] 不影响 `/oauth2/*`、OIDC、JWKS 与 actuator 的原有格式；gateway 关闭 `/iam/oauth2/token` 的现有行为保持不变
- [ ] 测试：在现有 `GatewaySecurityTest` 的状态码断言基础上，增加对信封 `code`、`traceId` 与 `X-Trace-Id` 的断言；503 与路由 404 有覆盖；服务侧 401、403 信封有覆盖；一次经 gateway 的请求，下游日志中的 traceId 与响应头一致
