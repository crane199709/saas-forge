# 登录显式选择 Platform 或 Tenant 上下文

> 适用范围：现行 v1 与历史决策。#201/#202 已确认统一登录不预选 Intent；新协议设计见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md)（v2 已实现，启用仍须受控切换）。下文不再作为新版登录目标，v1 实现及历史基线保持原状。

邮箱密码登录以可选的 `contextType` 表达 Login Context Intent，枚举仅为 `PLATFORM`、`TENANT`，缺省为 `TENANT`，且不接受 Tenant 或 Membership ID。IAM 根据该意图校验 Platform Role 或 Accessible Membership，而不根据 Origin、Host 或控制台类型隐式推断；这样同一 Identity 同时拥有 Platform Role 和多个 Tenant Membership 时仍有确定行为，也不会把浏览器部署拓扑变成认证授权输入。该字段保持可选以兼容已冻结的 v1 请求，Platform Console 必须显式发送 `PLATFORM`。

[ADR 0038](0038-browser-sessions-use-intent-bound-slots.md) 要求受控 Origin 只能操作与自身配对的 Browser Session Slot。这是对已显式提交的 `contextType` 或 `sessionSlot` 执行来源一致性校验，不改变 IAM 必须独立验证 Platform Role 或 Accessible Membership、不得从 Origin 推断 Login Context Intent 的决策。
