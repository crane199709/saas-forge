# 02: 信封模型与 forge-sdk-web

**What to build:** 业务服务引入 `forge-sdk-web` 后，Controller 返回 `ApiResponse<T>` 即得到统一信封；抛出 `ForgeException` 或发生框架异常时，自动输出带 `code`、`errorMessage`、`userTip`、`traceId` 的错误信封；分页参数按统一约定解析与校验。信封与错误模型放在 `forge-sdk-core`，以便 WebFlux 网关复用。

**Blocked by:** 01 ADR 与编码规范

**Status:** resolved

- [x] `forge-sdk-core` 提供 `ApiResponse<T>`、`FieldError`、`PageMeta`、`ErrorCode` 接口、`ForgeException`，不引入 Spring 依赖；public API 有文档注释
- [x] `ApiResponse` 提供 `ok`、`created`、`page` 等工厂方法；成功 `code = "ok"`，`httpStatus` 与实际状态码一致；null 字段不输出（Boot 4 使用 Jackson 3，确认配置方式对信封生效）
- [x] 框架级错误码（spec 5.2）的 `zh-CN` 文案随 SDK 提供，服务不做任何配置即可解析，且不影响服务自己的 `messages*.properties`；查不到文案时回退为通用提示
- [x] 新建 `forge-sdk-web`（Servlet 自动配置）并加入 BOM：全局异常映射覆盖 spec 5.2 中的 400、404、405、500 场景与 `ForgeException`；Bean Validation 失败输出 `errors[]`；5xx 的 `errorMessage` 固定为 `Internal error`，完整异常写日志
- [x] `ResponseBodyAdvice` 为信封填入当前 `traceId`；引入 Micrometer Tracing OTel bridge，W3C `traceparent` 传播，日志 pattern 输出 traceId，不配置 exporter
- [x] 分页参数解析：`page` 从 1 开始，`size` 默认 20、上限 100，`sort=字段,asc|desc` 可重复且须在接口声明的白名单内；违规返回 400 `request.invalid`
- [x] springdoc 生成的 schema 如实反映 `ApiResponse<具体类型>`，错误响应 schema 可见
- [x] 模块依赖无循环：`forge-sdk-core` 不依赖其他 SDK 模块，`forge-sdk-web` 只依赖 core
- [x] 测试：成功与错误信封结构；spec 5.2 各场景的状态码、`code`、`userTip`；5xx 不含异常信息；`Accept-Language` 缺省时用 `zh-CN`；分页参数默认值、上限、白名单；traceId 在信封与日志中一致

## Comments

2026-10-26 实现：`forge-sdk-core` 新增 `io.saasforge.sdk.core.api` 包（`ApiResponse`、`FieldError`、`PageMeta`、`ErrorCode`、`ForgeException`、`FrameworkErrorCode`、`FrameworkMessages`），null 字段由 `@JsonInclude(NON_NULL)` 注解排除（core 只多依赖 `jackson-annotations`，Jackson 3 与网关同样识别）；框架级文案放在独立 ResourceBundle `forge-messages.properties`，不替换服务的 MessageSource。新建 `forge-sdk-web` 并加入 BOM：`ApiExceptionHandler`、`EnvelopeResponseAdvice`（填 traceId 并按 `httpStatus` 设置状态码）、`UserTipResolver`（服务文案 → SDK 文案 → 通用提示）、`LocaleResolver` 缺省 zh-CN、`PageQuery` + `@SortableFields` 分页解析、springdoc 分页参数定制；tracing 用 `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel`，不带 exporter，日志沿用 Boot 自动加入的 traceId 关联 pattern。
- 偏离与约定：spec 5.2 之外的框架 4xx（415、406 等）保留实际状态码、code 用 `request.malformed`；创建接口需加 `@ResponseStatus(CREATED)` springdoc 才会把 201 写进文档；springdoc 只给每个接口列出 400、500 错误响应（404/405 处理方法对应受检异常，被 springdoc 过滤）。兜底 500 处理会吞掉方法安全抛出的 `AccessDeniedException`，接入 forge-sdk-security 时（工单 03）需处理。
- 验证：`./mvnw -pl forge-sdk/forge-sdk-web -am verify` 通过（core 15 项、web 24 项测试，最小测试应用 + MockMvc）；完整 `./mvnw verify` 通过。

2026-10-26 审查修复：`forge-sdk-web` 把 `DataAccessResourceFailureException` 体系（含 `RedisConnectionFailureException`）映射为 503 `system.unavailable`（新增 `spring-tx` 依赖）；新增 `EnvelopeErrorController` 取代 Boot 的 `BasicErrorController`，过滤器层抛出的异常经 `/error` 重新抛给 `ApiExceptionHandler`，`sendError` 按状态码映射框架级 code，traceId 在观测 scope 关闭后从请求的观测上下文取。信封写出的共用部分下沉到 core 的 `ErrorEnvelopes`（默认语言、`Internal error`、`Accept-Language` 解析、框架信封构造、序列化、traceId），core 新增 `jackson-databind` 与可选 `micrometer-tracing`；sdk-web 新增 public `EnvelopeErrorWriter` 供 IAM 复用。验证：新增 `ContainerErrorEnvelopeTest` 3 项（真实 Tomcat）、`ErrorEnvelopeTest` 503 一项、`ErrorEnvelopesTest` 5 项；完整 `./mvnw verify` 通过。
