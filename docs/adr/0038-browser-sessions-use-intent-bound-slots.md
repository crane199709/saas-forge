# 浏览器会话使用绑定 Intent 的独立槽位

> 适用范围：现行 v1 与历史决策。#201/#202 已确认单 Console、单一当前会话和整体退出；新协议及旧登录退役见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md)（v2 已实现，启用仍须受控切换）。本文一次性 v1 豁免保持原边界，不授权新统一协议改写基线。

Platform Console 与 Tenant Console Shell 虽然位于不同受控 Origin，但它们都请求同一 `api.<root>` 并共享原 `__Host-sf_refresh` host-only Cookie，因而无法在同一浏览器配置中同时维持可独立刷新和登出的 Platform 与 Tenant 会话。浏览器会话改为两个 Browser Session Slot：`__Host-sf_platform_refresh` 只定位 Platform Refresh Token Family，`__Host-sf_tenant_refresh` 只定位 Tenant 或 Tenant 待选择 Family；两者均保持 `Secure`、`HttpOnly`、`SameSite=Strict`、`Path=/` 且不设置 `Domain`。

登录继续以 `contextType` 表达 Login Context Intent，刷新与登出则以 `sessionSlot` 选择 Browser Session Slot；`platform.<root>` 只能提交 `PLATFORM`，`console.<root>` 只能提交 `TENANT`。该配对是对显式意图的来源一致性校验，不允许 IAM 从 Origin 推断授权上下文；IAM 仍必须独立校验 Platform Role、Accessible Membership、槽位与 Family Purpose。Tenant Context 选择与切换固定使用 Tenant 槽位，Initial Credential Session 固定使用 Platform 槽位，普通登出只终止所选槽位。

该决策有意修改已发布 v1 的浏览器登录、刷新、登出及 Cookie/security 语义，不为它们并行保留旧单槽位协议。这是仅限该操作的一次性 v1 例外：历史兼容基线仍不可修改，兼容门禁必须精确限定例外，全部第一方消费者必须原子升级，旧 `__Host-sf_refresh` 必须清除且旧浏览器会话不迁移。仓库无法证明不存在外部 v1 消费者，因此其中断是被明确接受的发布风险，不得扩展为其他 v1 契约的常规豁免。具体迁移、状态机和验收见 [Console 认证 Runtime 与浏览器会话规格](../28-console-authentication-runtime.md)。
