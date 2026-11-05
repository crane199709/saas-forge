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
