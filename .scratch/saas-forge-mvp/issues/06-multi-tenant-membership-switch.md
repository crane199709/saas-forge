# 06: 多租户成员与切换租户

**What to build:** 一个 Identity 在两个 Tenant 各有一个 Membership。登录后若只有一个有效 Membership 则自动进入，多个则先选择租户，没有则进入「无租户」页；进入后可随时切换租户，切换即用当前会话换发绑定新 Active Tenant 的 Token（ADR-0002），切换后只能看到新租户的订单。

**Blocked by:** 03 Tenant Realm 最小登录链路, 05 租户数据隔离

**Status:** ready-for-agent

- [ ] iam 提供「列出我的有效 Membership」与「切换租户（换发 Token）」能力
- [ ] 非 `active` 状态的 Membership 不可被选择或切换进入
- [ ] 切换到不属于自己的租户被拒绝
- [ ] `@forge/sdk` 提供租户选择与切换能力；tenant-app 有租户选择页与顶部租户切换器
- [ ] 切换租户后前端清空租户相关状态并重新加载
- [ ] 测试：同一 Identity 在租户 A、B 下看到的订单各不相同；切换前后 Token 的 `tenant_id` 与 `membership_id` 正确变化
