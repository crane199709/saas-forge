# 02: Tenant Realm 两步登录

**What to build:** 按 [spec](../spec.md) 2.3 节，已绑定 Authenticator 的 Identity 在密码登录或验证码登录后进入「待 MFA」状态，提交动态码或 Recovery Code 后才认证成功；授权记录本次是否完成 MFA。

**Blocked by:** 01

**Status:** ready-for-agent

- [x] `/auth/login`、`/auth/code-login` 对已绑定者不再直接建立已认证会话，响应指明下一步为 MFA；未绑定者行为不变
- [x] 新增 MFA 提交接口：动态码或 Recovery Code（用后作废）通过后建立已认证会话并返回原登录响应（memberships 等）
- [x] 「待 MFA」状态有有效期，过期后须重新提交 First Factor；待 MFA 状态不能发起授权码流程
- [x] 授权（含 Refresh Token 链）记录是否完成 MFA，refresh 与 switch 时可读取（工单 03 使用）
- [x] 审计 `auth.mfa`（成功或失败，区分动态码与 Recovery Code）
- [x] 测试覆盖：密码与验证码两种 First Factor、Recovery Code 只能用一次、过期的待 MFA 状态、待 MFA 时无法换取 Token

## Comments
