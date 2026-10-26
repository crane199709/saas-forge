# 所有业务接口响应统一套信封，Controller 显式返回 `ApiResponse<T>`

业务接口（含 gateway 与 Spring Security 产生的错误）的成功与失败响应都使用同一个响应信封 `{code, httpStatus, data, errorMessage, userTip, traceId, meta, errors}`，null 字段不输出，完整定义见 [统一接口格式规格](../../.scratch/unified-api-envelope/spec.md)。只统一错误、成功仍返回裸 `record` 时，前端要按状态码区分两套解析逻辑，成功响应也无处放 `traceId` 与分页元数据；统一信封后前端用一套逻辑解析，用户报障凭 `traceId` 定位日志。`/oauth2/*`、OIDC 发现与 JWKS 端点遵守 RFC 6749 / OIDC，actuator 与静态资源也不套信封。

- **HTTP 状态码如实返回**：状态码反映结果类别（201、400、401、403、404、503 等），业务细分靠 `code`，`httpStatus` 字段与之相同。不采用「一律 200 + 业务码」，否则网关、监控与浏览器缓存都无法按状态码区分成败。
- **不再使用 204**：204 不能携带响应体，也就带不出 `traceId`；删除返回 200 且 `data` 为 null。
- **点分错误码兼作 i18n key**：`code` 形如 `<领域>.<原因>`（如 `order.notFound`），既是稳定的机器可读标识，也直接作为文案 key 按 `Accept-Language` 生成 `userTip`，不再维护数字码与文案 key 两套映射。`errorMessage` 面向开发者，英文、不做国际化；5xx 时固定为 `Internal error`。
- **Controller 显式返回 `ApiResponse<T>`**：用 `ApiResponse.ok(x)`、`ApiResponse.created(x)` 构造，不由 `ResponseBodyAdvice` 自动包装。自动包装会让方法签名与实际响应不一致，springdoc 生成的 schema 失真，`String` 返回值等边界情况也要特殊处理；显式返回让契约从签名即可看出，并可用 ArchUnit 检查。Advice 只负责填充 `traceId` 与异常映射。
- **默认 offset 分页**：`page` 从 1 开始、`size` 默认 20 上限 100，分页信息放 `meta.page`。管理后台列表需要跳页与总数，offset 最直接；`meta.page` 预留 `nextCursor`、`hasMore` 以便将来为大表增加游标分页，本期不实现。

## Considered Options

- RFC 9457 Problem Details（`application/problem+json`）：只规范错误响应，成功响应仍需另定格式，不能解决「一套逻辑解析」；其 `type` 是 URI，`title`/`detail` 也没有区分开发者信息与用户文案，要满足需求仍得加扩展字段，标准带来的收益有限。
- 只统一错误格式：见上文，成功响应缺少 `traceId` 与分页元数据的位置。
