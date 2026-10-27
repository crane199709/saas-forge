# 01: ADR 与编码规范

**What to build:** 把统一响应格式的决策和约束写成可长期引用的文档，供后续工单与业务服务开发遵守。新增 ADR-0015，在编码规范中增加接口格式规则，在 GLOSSARY 中补充术语。只改文档，不改代码。

**Blocked by:** 无

**Status:** ready-for-agent

- [x] 新增 `docs/adr/0015-unified-api-envelope.md`，格式与现有 ADR 一致，记录：所有响应套信封而非只统一错误；不用 RFC 9457 Problem Details 的原因；HTTP 状态码如实返回；不再使用 204；点分错误码兼作 i18n key；Controller 显式返回 `ApiResponse<T>` 而非 Advice 自动包装；默认 offset 分页
- [x] `docs/agents/coding-standards.md` 新增「接口格式」一节：Controller 公开方法返回 `ApiResponse<T>`；错误码 `<领域>.<原因>` 命名，由功能包 `service` 层的 `ErrorCode` 枚举定义，业务异常抛 `ForgeException`；分页与排序参数约定、排序字段白名单；`errorMessage` 与 `userTip` 的分工及 5xx 脱敏
- [x] AGENTS.md 的 Coding standards 摘要补一句接口格式规则
- [x] `GLOSSARY.md` 补充「响应信封（Response Envelope）」「错误码（Error Code）」术语，并写明应避免的近义叫法
- [x] 文档内容与 [spec](../spec.md) 一致，示例取自 spec 第 3 节

## Comments

2026-10-26 实现：新增 ADR-0015 记录信封、状态码、204、点分错误码、显式返回 `ApiResponse<T>` 与 offset 分页的决策及不用 RFC 9457 的原因；编码规范新增「接口格式」一节（示例取自 spec 第 3 节）；AGENTS.md 摘要补充接口格式规则；GLOSSARY 新增「响应信封」「错误码」及应避免的叫法。仅改文档，无代码验证项。
