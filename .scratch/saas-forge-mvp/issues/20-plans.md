# 20: 套餐

**What to build:** Operator 在 platform-console 维护 Plan（Feature Pack：可用 Permission Code 与 Sub-App；Quota：成员数、存储容量等）并分配给 Tenant。Tenant Admin 授权时只能在套餐范围内选择；租户降级后超出新套餐的授权自动失效但保留记录，升级后自动恢复；成员数配额生效。不做计费与支付。

**Blocked by:** 14 租户创建与开通, 16 租户角色管理

**Status:** ready-for-agent

- [ ] Plan 的增删改；Feature Pack 从 Resource Manifest 中选择权限码
- [ ] 创建 Tenant 时选择 Plan，之后可变更；变更经 Outbox 广播 `TenantPlanChanged`
- [ ] 角色授权界面只展示套餐内的权限码；越界授权被服务端拒绝
- [ ] 降级后超限授权失效（成员即时失去该权限），记录保留；升级后恢复
- [ ] 成员数配额：达到上限后邀请被拒
- [ ] Feature Pack 中的 Sub-App 列表可维护（加载控制在后续工单实现）
- [ ] 测试：降级后成员访问超限接口得到 403，升级后恢复；超出成员配额的邀请被拒
