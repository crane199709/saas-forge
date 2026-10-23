# 17: 成员管理

**What to build:** Tenant Admin 在 tenant-app 按邮箱或手机号邀请成员加入本租户（已存在的 Identity 收到邀请，不存在的收到注册邀请），可以停用、恢复、移除成员；Owner 可以把所有者身份转让给另一个成员。停用立即生效。Tenant Admin 不能修改成员的全局凭证。

**Blocked by:** 14 租户创建与开通, 16 租户角色管理

**Status:** ready-for-agent

- [ ] 邀请复用开通流程中的邀请机制，可在邀请时预设角色与部门
- [ ] 成员列表展示 Membership 状态（邀请中、正常、停用、已离开），支持搜索与筛选
- [ ] 停用、恢复、移除成员；停用即时生效
- [ ] 成员可主动退出租户（Owner 除外）
- [ ] Owner 转让后原 Owner 降为 Tenant Admin；租户始终恰有一个 Owner
- [ ] Tenant Admin 无法修改 Identity 的密码、邮箱、手机号
- [ ] 测试：停用成员下一次请求被拒；Owner 不可被停用或移除
