# 08: tenant-app 的账号安全界面

**What to build:** 按 [spec](../spec.md) 第 7 节实现 tenant-app 的登录 MFA 步骤、强制绑定、忘记密码、「账号安全」个人页与租户「安全设置」页。

**Blocked by:** 02, 03, 07

**Status:** ready-for-agent

- [ ] 登录页：MFA 步骤（可切换为 Recovery Code）；遇到 `auth.mfa_required` 时进入强制绑定步骤（二维码、首个动态码、展示 Recovery Code）后重试进入
- [ ] 忘记密码页
- [ ] 「账号安全」个人页：改密码、绑定或解绑、重新生成 Recovery Code（只展示一次，可复制与下载）
- [ ] 租户管理「安全设置」页：MFA Requirement 开关，按 `iam:security:manage` 显示
- [ ] `auth.locked`、`password.*`、`auth.mfa_required` 的 i18n 文案
- [ ] 组件测试覆盖登录两步、强制绑定与锁定提示

## Comments

- 2026-11-05（工单 03 实现时记录）：后端错误码为 `auth.mfaRequired`（403，即规格中的 `auth.mfa_required`），i18n key 用这个。exchange 被拒时：凭登录会话调用 `/auth/mfa/bind/start`、`/auth/mfa/bind/confirm`，确认成功后会话标识会换发（浏览器自动处理 Cookie），须重新发起 `/oauth2/authorize` 取新授权码再 exchange，原授权码不可重试。switch/refresh 被拒时：凭当前 Access Token 绑定，成功后用原 Refresh Token 直接重试。安全设置接口为 `GET/PUT /iam/security-settings`，体为 `{"mfaRequired": boolean}`。
- 2026-11-05（工单 06 实现时记录）：Login Lockout 锁定时返回 HTTP 429，信封 `code` 为 `auth.locked`（i18n key 用这个），剩余等待秒数在 `Retry-After` 响应头（整数秒），`userTip` 已含秒数；信封 `data` 为空。可能出现在 `/auth/login`、`/auth/code-login`、`/auth/login/mfa`、`/auth/mfa/{bind/confirm,recovery-codes/regenerate,unbind}`、`/auth/password/change`、`/auth/password/forgot/{verify,reset}`。达到阈值的那次仍返回原错误码（如 `auth.failed`、`mfa.codeInvalid`），下一次才返回 `auth.locked`；锁定期内正确凭证同样被拒，到期自动解除。
