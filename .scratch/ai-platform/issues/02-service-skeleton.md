# 02: AI 服务骨架与平台接入

**What to build:** 按 [spec](../spec.md) 的“实现决策”，用 `forge-service-archetype` 生成 Java AI 服务，接入 Forge SDK、独立 schema、Manifest 与网关。

**Blocked by:** 00, 01

**Status:** ready-for-agent

- [ ] 服务继承 Forge 依赖与父 POM，接入 tenant、security、audit、web、manifest、provisioning、scheduling
- [ ] 独立 schema 与迁移账号；平台级表与租户级表分开，租户表带标准列
- [ ] 健康检查、统一响应信封、错误码、OpenAPI
- [ ] Resource Manifest 声明权限码、菜单、角色模板与 Sub-App 编码 `ai`
- [ ] App Registry 注册与网关 `/ai/**` 路由
- [ ] 服务凭证、租户上下文、审计落库打通
- [ ] 集成测试覆盖：健康检查、未认证 401、跨租户不可见、审计事件落库

## Comments
