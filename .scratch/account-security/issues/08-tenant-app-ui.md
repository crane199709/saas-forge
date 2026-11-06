# 08: tenant-app 的账号安全界面

**What to build:** 按 [spec](../spec.md) 第 7 节实现 tenant-app 的登录 MFA 步骤、强制绑定、忘记密码、「账号安全」个人页与租户「安全设置」页。

**Blocked by:** 02, 03, 07

**Status:** resolved

- [x] 登录页：MFA 步骤（可切换为 Recovery Code）；遇到 `auth.mfa_required` 时进入强制绑定步骤（二维码、首个动态码、展示 Recovery Code）后重试进入
- [x] 忘记密码页
- [x] 「账号安全」个人页：改密码、绑定或解绑、重新生成 Recovery Code（只展示一次，可复制与下载）
- [x] 租户管理「安全设置」页：MFA Requirement 开关，按 `iam:security:manage` 显示
- [x] `auth.locked`、`password.*`、`auth.mfa_required` 的 i18n 文案
- [x] 组件测试覆盖登录两步、强制绑定与锁定提示

## Comments

- 2026-11-05（工单 03 实现时记录）：后端错误码为 `auth.mfaRequired`（403，即规格中的 `auth.mfa_required`），i18n key 用这个。exchange 被拒时：凭登录会话调用 `/auth/mfa/bind/start`、`/auth/mfa/bind/confirm`，确认成功后会话标识会换发（浏览器自动处理 Cookie），须重新发起 `/oauth2/authorize` 取新授权码再 exchange，原授权码不可重试。switch/refresh 被拒时：凭当前 Access Token 绑定，成功后用原 Refresh Token 直接重试。安全设置接口为 `GET/PUT /iam/security-settings`，体为 `{"mfaRequired": boolean}`。
- 2026-11-05（工单 06 实现时记录）：Login Lockout 锁定时返回 HTTP 429，信封 `code` 为 `auth.locked`（i18n key 用这个），剩余等待秒数在 `Retry-After` 响应头（整数秒），`userTip` 已含秒数；信封 `data` 为空。可能出现在 `/auth/login`、`/auth/code-login`、`/auth/login/mfa`、`/auth/mfa/{bind/confirm,recovery-codes/regenerate,unbind}`、`/auth/password/change`、`/auth/password/forgot/{verify,reset}`。达到阈值的那次仍返回原错误码（如 `auth.failed`、`mfa.codeInvalid`），下一次才返回 `auth.locked`；锁定期内正确凭证同样被拒，到期自动解除。
- 2026-11-05（实现时记录）：后端补了 `GET /auth/mfa`（Access Token，返回 `{"bound": boolean}`），账号安全页据此决定展示绑定还是重新生成/解绑；已写入 docs/tenant-login.md 接口表，IdentityMfaTest 覆盖。SDK `login`/`loginWithCode`/新增的 `submitMfa` 结果新增 `mfa` 与 `mfaBindRequired`（唯一租户被 `auth.mfaRequired` 拒绝时）；多租户在选择页被拒时页面当场绑定。绑定接口在已登录时带 Access Token，否则凭登录会话 Cookie。`auth.locked` 的剩余时间按工单 06 Comments 中约定的 `Retry-After` 头读取（SDK `ApiError.retryAfter`），换算成分钟展示；`password.*`、`auth.mfaRequired` 优先展示服务端 userTip，没有 userTip 时用前端 `errorCode.*` 文案。
- 2026-11-05：未覆盖的边界——refresh 被 `auth.mfaRequired` 拒绝且 Access Token 已过期时，无法凭 Token 当场绑定，只能重新登录后走强制绑定；切换租户被拒时只展示 userTip，用户可到「账号安全」绑定后重试切换。
- 2026-11-06（code-review 修复，取代上方 2026-11-05「未覆盖的边界」）：refresh 被 `auth.mfaRequired` 拒绝时 SDK 经 `onMfaRequired` 通知，路由守卫进入 `/mfa-bind`，绑定后 `resumeEntry` 重试并回到原页面；switch 被拒在切换器对话框中当场绑定后重试。绑定接口先走 Access Token，刷新被拒时改走登录会话；登录会话也失效（401）或身份已在别处绑定（`mfa.alreadyBound`）时清除会话回登录页。`RecoveryCodeList`、`MfaLoginForm` 移入 `@crane199709/ui`（该包只用 `tsc` 构建，不支持 SFC，故改写为 render 函数），文案随组件进入 UI 包 local scope。
