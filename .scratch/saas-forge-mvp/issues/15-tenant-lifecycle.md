# 15: 租户生命周期

**What to build:** Operator 在 platform-console 按状态机推进 Tenant Lifecycle（待开通、试用、正常、停用、过期、归档、删除）。每次变更广播到各服务：停用或过期后租户成员访问被网关拒绝、SDK 拒绝写入；删除进入 Cooling-off Period（默认 30 天，可配置），期内可撤回，到期后广播 `TenantDeleted`，各服务清理本服务数据。到期提醒在试用或订购到期前触发。

**Blocked by:** 07 Token 即时失效与刷新, 14 租户创建与开通

**Status:** ready-for-agent

- [ ] 状态机以显式的合法迁移表定义，非法迁移被拒绝
- [ ] 每次状态变更经 Outbox 广播 `TenantStatusChanged`，并更新网关可读取的租户状态
- [ ] 停用、过期租户的成员请求被拒（即时生效）
- [ ] SDK 提供按租户状态拒绝写入的默认行为，服务可声明例外
- [ ] 删除进入冷静期，期内可撤回；到期后广播 `TenantDeleted`，demo-service 清理本服务该租户数据
- [ ] 到期提醒经邮件/短信 SPI 发送给 Owner
- [ ] platform-console 可执行状态迁移并查看变更历史
- [ ] 测试：非法迁移被拒；停用后成员下一次请求被拒；冷静期结束后 demo-service 数据被清理
