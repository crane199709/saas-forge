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
- 2026-11-05（工单 08 实现时记录）：SDK 的 `authenticate` 遇到 `nextStep: "mfa"` 时直接返回（不保留授权参数），`createPlatformClient().login` 目前仍随即调用 `authorize`，Operator MFA 上线后需像 Tenant 一样返回 MFA 步骤；`ApiError.retryAfter`（`auth.locked`）可直接复用。tenant-app 的 `security/RecoveryCodeList.vue`、`security/MfaBindForm.vue` 可作参考，platform-console 不能直接 import tenant-app。
