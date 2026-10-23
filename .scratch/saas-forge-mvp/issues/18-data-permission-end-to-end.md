# 18: 数据权限端到端（Data Scope 预设）

**What to build:** Business Service 在 Resource Manifest 中声明 Data Resource 及其可用属性（映射到表列）；Tenant Admin 在 Role 上为某 Data Resource 选择 Data Scope（本人、本部门、本部门及下级、全部）。demo-service 的订单列表、更新、删除自动按当前成员的数据范围过滤，单条操作也做校验；多个 Role 的范围取并集。

**Blocked by:** 04 ABAC 表达式树引擎, 05 租户数据隔离, 13 组织模型, 16 租户角色管理

**Status:** ready-for-agent

- [ ] Resource Manifest 支持声明 Data Resource、属性、属性类型与对应表列
- [ ] Data Policy 挂在 Role 上并按 Data Resource 存储
- [ ] SDK 数据权限拦截器把当前成员的策略编译为 SQL 谓词，注入列表、更新、删除语句
- [ ] SDK 提供单对象校验 API，使用内存求值，与 SQL 过滤结论一致
- [ ] 未配置任何策略的 Data Resource 默认行为明确（拒绝访问）并在文档中说明
- [ ] 策略变更递增受影响 Membership 的 version，即时生效
- [ ] tenant-app 在角色编辑中提供 Data Scope 选择
- [ ] Testcontainers 测试：不同部门、不同角色组合的成员看到的订单集合符合预期；越权更新、删除被拒
