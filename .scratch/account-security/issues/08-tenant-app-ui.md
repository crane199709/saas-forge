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
