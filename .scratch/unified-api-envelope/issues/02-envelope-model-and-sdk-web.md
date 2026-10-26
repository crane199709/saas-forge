# 02: 信封模型与 forge-sdk-web

**What to build:** 业务服务引入 `forge-sdk-web` 后，Controller 返回 `ApiResponse<T>` 即得到统一信封；抛出 `ForgeException` 或发生框架异常时，自动输出带 `code`、`errorMessage`、`userTip`、`traceId` 的错误信封；分页参数按统一约定解析与校验。信封与错误模型放在 `forge-sdk-core`，以便 WebFlux 网关复用。

**Blocked by:** 01 ADR 与编码规范

**Status:** ready-for-agent

- [ ] `forge-sdk-core` 提供 `ApiResponse<T>`、`FieldError`、`PageMeta`、`ErrorCode` 接口、`ForgeException`，不引入 Spring 依赖；public API 有文档注释
- [ ] `ApiResponse` 提供 `ok`、`created`、`page` 等工厂方法；成功 `code = "ok"`，`httpStatus` 与实际状态码一致；null 字段不输出（Boot 4 使用 Jackson 3，确认配置方式对信封生效）
- [ ] 框架级错误码（spec 5.2）的 `zh-CN` 文案随 SDK 提供，服务不做任何配置即可解析，且不影响服务自己的 `messages*.properties`；查不到文案时回退为通用提示
- [ ] 新建 `forge-sdk-web`（Servlet 自动配置）并加入 BOM：全局异常映射覆盖 spec 5.2 中的 400、404、405、500 场景与 `ForgeException`；Bean Validation 失败输出 `errors[]`；5xx 的 `errorMessage` 固定为 `Internal error`，完整异常写日志
- [ ] `ResponseBodyAdvice` 为信封填入当前 `traceId`；引入 Micrometer Tracing OTel bridge，W3C `traceparent` 传播，日志 pattern 输出 traceId，不配置 exporter
- [ ] 分页参数解析：`page` 从 1 开始，`size` 默认 20、上限 100，`sort=字段,asc|desc` 可重复且须在接口声明的白名单内；违规返回 400 `request.invalid`
- [ ] springdoc 生成的 schema 如实反映 `ApiResponse<具体类型>`，错误响应 schema 可见
- [ ] 模块依赖无循环：`forge-sdk-core` 不依赖其他 SDK 模块，`forge-sdk-web` 只依赖 core
- [ ] 测试：成功与错误信封结构；spec 5.2 各场景的状态码、`code`、`userTip`；5xx 不含异常信息；`Accept-Language` 缺省时用 `zh-CN`；分页参数默认值、上限、白名单；traceId 在信封与日志中一致
