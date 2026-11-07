# 01: 角色净影响预览

**What to build:** 按 spec 第 2 节，提供角色权限和数据策略变更的只读预览接口。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 权限预览计算净影响，测试覆盖多角色持有和超出 Plan 的情况
- [ ] 返回数量、前 20 名和净失去的 Permission Code，只统计状态正常的 Membership
- [ ] 数据策略预览返回持有该角色的成员
- [ ] 接口不写数据、不递增 Membership version
- [ ] 权限与修改接口一致，跨租户访问被拒绝

## Comments
