# 09: platform-console 的账号安全界面

**What to build:** 按 [spec](../spec.md) 第 7 节实现 platform-console 的登录 MFA 步骤与强制绑定、「账号安全」个人页、运营人员「重置 MFA」与「身份查询」页。

**Blocked by:** 04, 05, 07

**Status:** ready-for-agent

- [ ] 登录页：MFA 步骤；`mfa-required` 时的强制绑定步骤
- [ ] 「账号安全」个人页：改密码、更换 Authenticator、重新生成 Recovery Code
- [ ] 运营人员管理增加「重置 MFA」（Super Admin）
- [ ] 「身份查询」页：精确查找、展示是否已绑定、「重置 MFA」，按 `platform:identity-mfa:reset` 显示
- [ ] 组件测试覆盖登录两步、强制绑定与身份查询

## Comments
- 2026-11-05（工单 04 实现时记录）：`/iam/platform/auth/login` 响应新增 `nextStep`（`authorize`/`mfa`/`bind`），`mfa` 与 `bind` 时 `clientId`、`redirectUri` 为空；第二步接口为 `/iam/platform/auth/login/mfa`、`/iam/platform/auth/login/bind/{start,confirm}`（后者 201 并带只展示一次的 `recoveryCodes`），已登录后的「更换 Authenticator」为 `/iam/platform/auth/mfa/replace/start` 加 `/iam/platform/auth/mfa/bind/confirm`，详见 docs/platform-login.md。`auth.mfaRequired` 已改为 Tenant Realm 的「租户要求 MFA」语义，Operator 登录不再返回它；`LoginPage.spec.ts` 中以它为样例的错误展示用例宜改用其他错误码。
- 2026-11-05（工单 06 实现时记录）：Login Lockout 锁定时返回 HTTP 429，信封 `code` 为 `auth.locked`（i18n key 用这个），剩余等待秒数在 `Retry-After` 响应头（整数秒），`userTip` 已含秒数；信封 `data` 为空。可能出现在 `/iam/platform/auth/login`、`/iam/platform/auth/login/{mfa,bind/confirm}`、`/iam/platform/auth/mfa/{bind/confirm,replace/start,recovery-codes/regenerate,unbind}`、`/iam/platform/auth/password/change`。达到阈值的那次仍返回原错误码（如 `auth.failed`、`mfa.codeInvalid`），下一次才返回 `auth.locked`；锁定期内正确凭证同样被拒，到期自动解除。
- 2026-11-05（工单 08 实现时记录）：SDK 的 `authenticate` 遇到 `nextStep` 不是 `authorize`（`mfa`、`bind`）时直接返回（不保留授权参数），`createPlatformClient().login` 目前仍随即调用 `authorize`，Operator MFA 上线后需像 Tenant 一样返回 MFA 步骤；`ApiError.retryAfter`（`auth.locked`）可直接复用。tenant-app 的 `security/RecoveryCodeList.vue`、`security/MfaBindForm.vue` 可作参考，platform-console 不能直接 import tenant-app。
- 2026-11-05（工单 05 实现时记录）：「身份查询」页调 `POST /iam/platform/identities/lookup`，请求体 `{identifier}`，返回 `{identityId, displayName, mfaBound}`，查不到时 404 `mfa.identityNotFound`（不是空列表）；「重置 MFA」调 `POST /iam/platform/identities/{identityId}/mfa/reset`，页面与按钮按 `platform:identity-mfa:reset` 显示。运营人员管理的「重置 MFA」调 `POST /iam/platform/operators/{id}/mfa/reset`，仅超级管理员可用（非超级管理员 403 `auth.forbidden`），前端可按 `/iam/platform/access` 的 `roles` 含 `super-admin` 决定是否显示。两者对未绑定者返回 409 `mfa.notBound`，成功均为 200 空数据；需 i18n 文案：`mfa.identityNotFound`、`mfa.notBound`。
