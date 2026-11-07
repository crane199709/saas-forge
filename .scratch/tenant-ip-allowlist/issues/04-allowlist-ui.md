# 04: 界面

**What to build:** 在 `tenant-app` 安全设置页增加规则管理，在 platform-console 增加紧急清除，并为被拒绝的用户提供明确页面。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] 显示当前 IP，保存前提示生效范围，校验失败时定位到具体条目
- [ ] 收到 `tenant.ipNotAllowed` 时显示专门页面，可切换到其他 Tenant 或退出，不反复刷新 Token
- [ ] 平台端清除前需确认并填写原因
- [ ] Playwright 覆盖保存、被拒和清除

## Comments
