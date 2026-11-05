# 统一接口请求与响应格式规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-10-26 的设计拷问逐条确认，落地后由 ADR-0015 记录。

## 1. 背景与目标

现有接口直接返回业务 `record`，只有 IAM 的 `AuthenticationErrors` 返回 `{code, message}`；gateway 的错误是 WebFlux 默认格式；Spring Security 的 401/403 没有响应体；前端 `@forge/sdk` 只能按 HTTP 状态码猜测错误。没有 traceId，用户报障无法定位日志。

目标：

1. 所有业务接口的成功与失败响应使用同一个信封，前端用一套逻辑解析。
2. 错误同时给开发者（`errorMessage`）和最终用户（`userTip`）可用的信息，并带 `traceId` 便于排障。
3. 统一分页请求参数与分页响应结构。
4. 一次性迁移全部现有接口，不保留旧格式。

## 2. 适用范围

| 范围 | 是否使用信封 |
|---|---|
| 业务 REST 接口：IAM `/auth/*`、demo-service 全部接口、后续业务服务 | 是 |
| gateway 自身产生的错误：401、403、路由 404、下游不可用 503、未知异常 500 | 是 |
| Spring Security 在服务内产生的 401、403 | 是 |
| `/oauth2/*`、OIDC 发现与 JWKS 端点 | 否，遵守 RFC 6749 / OIDC |
| actuator | 否 |
| 静态资源与运行时配置（如 `sub-apps.json`） | 否 |

## 3. 响应信封

### 3.1 字段

| 字段 | 类型 | 出现时机 | 说明 |
|---|---|---|---|
| `code` | string | 总是 | 成功为 `"ok"`；失败为点分错误码 `<领域>.<原因>`，同时是 i18n key |
| `httpStatus` | int | 总是 | 与实际 HTTP 状态码相同，便于日志与排障 |
| `data` | any | 成功 | 业务数据；无数据时为 null（不输出） |
| `errorMessage` | string | 失败 | 面向开发者：英文、可带 id 等上下文、不做国际化，前端不展示 |
| `userTip` | string | 失败 | 面向最终用户：按 `Accept-Language` 以 `code` 查 i18n 文案，可直接展示 |
| `traceId` | string | 总是 | 当前请求的 W3C trace id |
| `meta` | object | 按需 | 附加元数据，目前只有分页 `meta.page` |
| `errors` | array | 校验失败 | 逐字段明细，每项 `{field, code, userTip}` |

值为 null 的字段一律不输出。不设 `success` 字段，避免与 `code` 重复。Content-Type 为 `application/json`，不使用 `application/problem+json`。

### 3.2 HTTP 状态码

HTTP 状态码如实反映结果类别，业务细分靠 `code`。创建资源返回 201；删除返回 200 且 `data` 为 null。不再使用 204，因为 204 不能携带响应体与 `traceId`。

### 3.3 示例

成功（201）：

```json
{
  "code": "ok",
  "httpStatus": 201,
  "data": { "id": "0192f3a1-...", "title": "办公用品", "amount": 128.50 },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

业务错误（404）：

```json
{
  "code": "order.notFound",
  "httpStatus": 404,
  "errorMessage": "Order 0192f3a1-... not found in current tenant",
  "userTip": "订单不存在或已被删除",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

校验错误（400）：

```json
{
  "code": "request.invalid",
  "httpStatus": 400,
  "errorMessage": "2 field(s) failed validation",
  "userTip": "请检查填写的内容",
  "traceId": "...",
  "errors": [
    { "field": "amount", "code": "validation.positive", "userTip": "金额必须大于 0" },
    { "field": "title", "code": "validation.notBlank", "userTip": "标题不能为空" }
  ]
}
```

分页（200）：

```json
{
  "code": "ok",
  "httpStatus": 200,
  "data": [ { "id": "...", "title": "...", "amount": 10 } ],
  "meta": { "page": { "number": 1, "size": 20, "totalElements": 135, "totalPages": 7 } },
  "traceId": "..."
}
```

## 4. 请求约定

- 请求体是纯业务 `record`，不套信封。JSON 字段用 camelCase。
- 租户一律从 Token 取得，请求中不出现 `tenantId` 作为隔离依据。
- 分页参数：`page` 从 1 开始；`size` 默认 20、上限 100，超出上限返回 400 `request.invalid`，不静默截断。
- 排序：`sort=字段,asc|desc`，可重复。字段必须在接口声明的白名单内，不在白名单返回 400。排序列名进入 SQL 时遵守编码规范中 `${}` 的白名单规则。
- 过滤条件用普通 query 参数。条件复杂到 query 放不下时使用 `POST /<资源>/search`，过滤条件放请求体，分页与排序参数仍放 query。
- 默认 offset 分页。`meta.page` 将来可扩展 `nextCursor`、`hasMore` 以支持游标分页，本期不实现。

## 5. 错误语义

### 5.1 错误码

- 点分字符串 `<领域>.<原因>`，领域小写、原因 lowerCamelCase，如 `auth.tenantAccessDenied`、`order.notFound`。
- 业务异常统一抛 `ForgeException(ErrorCode code, Object... args)`。`ErrorCode` 是接口，提供 `code()` 与 `httpStatus()`；每个功能包在 `service` 层定义自己的枚举，如 `OrderErrorCode.NOT_FOUND("order.notFound", 404)`。`args` 填充 i18n 文案占位符；可显式提供 `errorMessage`，否则由 code 与参数生成。
- 同一 code 只在一个枚举中定义，状态码由枚举固定。

### 5.2 框架异常映射

| 场景 | HTTP | code |
|---|---|---|
| Bean Validation 校验失败、分页/排序参数非法 | 400 | `request.invalid`（附 `errors`） |
| JSON 格式错误、参数类型不匹配 | 400 | `request.malformed` |
| 路由不存在 | 404 | `request.notFound` |
| 请求方法不支持 | 405 | `request.methodNotAllowed` |
| 网关按租户限流，超过阈值（saas-forge-mvp 工单 11） | 429 | `request.rateLimited`（附 `Retry-After`） |
| 未认证、Token 无效或已失效 | 401 | `auth.unauthenticated` |
| 已认证但无权限 | 403 | `auth.forbidden` |
| 网关下游不可用或超时、依赖的 Redis 不可用 | 503 | `system.unavailable` |
| 其他未知异常 | 500 | `system.error` |

现有业务码保留：`auth.failed`（401，账号或密码错误、登录会话失效）、`auth.tenantAccessDenied`（403）。

### 5.3 文案与脱敏

- 框架级错误码（`request.*`、`auth.unauthenticated`、`auth.forbidden`、`system.*`）的文案由 SDK 提供，服务无需额外配置即可解析；业务错误码的文案放在各服务的 `messages*.properties`。
- 默认语言 `zh-CN`。查不到文案时 `userTip` 回退为「操作失败，请稍后重试」，不向用户展示 code。
- 5xx 与未知异常的 `errorMessage` 固定为 `Internal error`，不输出异常信息与堆栈；完整异常记录到日志，凭 `traceId` 查询。

## 6. traceId

- gateway 与 Servlet 服务引入 Micrometer Tracing 的 OTel bridge，以 W3C `traceparent` 跨服务传播；gateway 生成 trace，下游继承。
- 日志 pattern 输出 traceId。gateway 在响应头回写 `X-Trace-Id`，信封中的 `traceId` 与之相同。
- 本期不配置 exporter，不接采集后端。

## 7. 实现结构

- `forge-sdk-core`：信封与错误模型的纯 Java 类型（`ApiResponse<T>`、`FieldError`、`PageMeta`、`ErrorCode`、`ForgeException`）及框架级错误码文案。不引入 Spring 依赖，Servlet 服务与 WebFlux 网关共用。
- `forge-sdk-web`（新建，Servlet 自动配置）：全局 `@RestControllerAdvice` 异常映射、填充 `traceId` 的 `ResponseBodyAdvice`、按 code 生成 `userTip`、分页参数解析与校验、springdoc 信封 schema 适配、tracing bridge。
- `forge-sdk-security`：替换默认 `AuthenticationEntryPoint` 与 `AccessDeniedHandler`，输出信封。
- `forge-gateway`：`ErrorWebExceptionHandler` 与安全处理器输出信封，回写 `X-Trace-Id`。
- Controller 显式返回 `ApiResponse<T>`（如 `ApiResponse.ok(x)`、`ApiResponse.created(x)`），不由 Advice 自动包装。
- 前端 `@forge/sdk`：新增 `api<T>()` 返回 `data`、`page<T>()` 返回 `{ items, page }`，失败抛 `ApiError`（含 `code`、`httpStatus`、`userTip`、`traceId`、`errors`）；`request()` 保留给需要原始 `Response` 的场景；`auth.ts` 按 `code` 判断错误。页面展示 `userTip` 并附可复制的 traceId。

## 8. 对 saas-forge-mvp 的影响

已逐项核对 [saas-forge-mvp 规格](../saas-forge-mvp/spec.md) 与工单：

| 位置 | 影响 | 处理 |
|---|---|---|
| 规格 §4 仓库结构 | 新增 `forge-sdk-web` 模块 | 已补入规格 §4 |
| 规格 §13、工单 11 可观测性 | 本规格引入 tracing bridge、日志 traceId 与 `X-Trace-Id`；与工单 11 的「trace 连续」重叠 | 已在工单 11 评论中说明分工。工单 11 保留：MDC 写入 `tenant_id`、`membership_id`，OTel exporter，指标，Sentinel 限流；限流响应使用信封，HTTP 429、code `request.rateLimited` |
| 工单 03、06、07（已完成） | 登录、切换、刷新、退出接口响应变为信封，退出由 204 改为 200；`auth.failed`、`auth.tenantAccessDenied` 语义不变 | 由本规格工单 04 迁移并更新 [登录说明](../../docs/tenant-login.md) |
| 工单 05（已完成） | 跨租户 404 改为 `order.notFound` 信封；订单列表改为分页 | 由本规格工单 04 迁移 |
| 工单 12、16、22 | 无权限 403 使用 `auth.forbidden` 信封；菜单接口使用信封 | 实现时遵守本规格，无需改工单 |
| 工单 13、14、17、21、22、23 | 列表、搜索、筛选（成员、租户、审计等）使用统一分页与排序约定 | 实现时遵守本规格 |
| 工单 10、19、20、24 | 验证码超频、非法表达式、配额超限等以功能包 `ErrorCode` 表达；表达式校验错误可用 `errors` 携带明细 | 实现时遵守本规格 |
| 工单 24 对象存储 | 文件经预签名 URL 直连存储，不经接口传输二进制 | 无影响 |
| 工单 26 ArchUnit | 可增加「Controller 公开方法返回 `ApiResponse`」规则 | 已补入工单 26 验收项 |
| 规格 §12「第一期只支持简体中文」 | 默认 `zh-CN` 与之一致；`Accept-Language` 解析为后续多语言预留 | 无影响 |

## 9. 不在本期

游标分页实现、OTel exporter 与采集后端、新旧响应格式兼容层、`problem+json` 输出、按接口自定义的错误响应模板。

## 10. 验收标准

- 第 2 节中所有使用信封的接口，成功与失败响应均符合第 3 节结构，null 字段不输出。
- 第 5.2 节每个场景都有测试断言 HTTP 状态码、`code` 与 `userTip`；5xx 响应不含异常信息。
- 同一请求经 gateway 到下游服务，响应头 `X-Trace-Id`、信封 `traceId` 与下游日志中的 traceId 一致。
- 分页：`page`、`size`、`sort` 的默认值、上限与白名单按第 4 节生效；demo-service 订单列表返回 `meta.page`。
- springdoc 生成的 schema 如实反映信封与 `data` 的具体类型。
- 前端 SDK 与三个 app 的测试、类型检查、构建、Playwright 回归通过；错误提示展示 `userTip` 与 traceId。
- 后端 `./mvnw verify` 通过。
