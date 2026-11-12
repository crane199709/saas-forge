# 02: 会话信息迁移与 demo 依赖移除

**What to build:** 按 [spec](../spec.md) 的“实现决策”，把首页的身份/租户/成员 ID 移到账号安全页的“当前会话”，并移除首页对 `/demo/context` 的调用。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 账号安全页新增“当前会话”卡片：身份名、租户名、Identity ID、Tenant ID、Membership ID
- [ ] 首页不再显示任何 ID，也不再调用 demo-service
- [ ] 租户上下文校验保留在主链路 E2E
- [ ] 旧的首页组件与相关 i18n、测试 ID 清理
- [ ] 组件测试覆盖当前会话字段与权限
- [ ] 切换租户后当前会话与工作台同步刷新

## Comments
