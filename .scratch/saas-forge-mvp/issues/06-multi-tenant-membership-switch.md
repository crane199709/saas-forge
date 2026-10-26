# 06: 多租户成员与切换租户

**What to build:** 一个 Identity 在两个 Tenant 各有一个 Membership。登录后若只有一个有效 Membership 则自动进入，多个则先选择租户，没有则进入「无租户」页；进入后可随时切换租户，切换即用当前会话换发绑定新 Active Tenant 的 Token（ADR-0002），切换后只能看到新租户的订单。

**Blocked by:** 03 Tenant Realm 最小登录链路, 05 租户数据隔离

**Status:** ready-for-agent

- [x] iam 提供「列出我的有效 Membership」与「切换租户（换发 Token）」能力
- [x] 非 `active` 状态的 Membership 不可被选择或切换进入
- [x] 切换到不属于自己的租户被拒绝
- [x] `@forge/sdk` 提供租户选择与切换能力；tenant-app 有租户选择页与顶部租户切换器
- [x] 切换租户后前端清空租户相关状态并重新加载
- [x] 测试：同一 Identity 在租户 A、B 下看到的订单各不相同；切换前后 Token 的 `tenant_id` 与 `membership_id` 正确变化


## Comments

2026-10-26 实现与验收：
- 登录只认证全局 Identity，响应列出 Identity、Membership、Tenant 三者均 active 的 Membership；SDK `login` 返回 `signedIn` / `selectTenant` / `noTenant`。
- 选择租户经 `/auth/exchange` 的 `membershipId`、切换租户经 `/auth/switch`（带 `membership_id` 的标准 refresh_token grant），均由 Token 定制器在已认证 Identity 自己的有效 Membership 中校验，越权或非 active 返回 403 `auth.tenantAccessDenied`，被拒绝的切换不消费刷新凭证。`GET /auth/memberships` 以 Access Token 无状态验签。
- `@forge/sdk` 新增 `selectTenant`、`memberships`、`switchTenant`、`onSessionChange`，刷新与切换共用轮换队列；tenant-app 新增租户选择页、无租户页与顶部切换器，切换后整页回到 `/`。
- 验证：后端 `./mvnw verify` 通过（IAM 16 项含 4 项新增多租户集成测试；demo-service 新增同一 Identity 在 A、B 两个 Membership 下订单互不可见；网关新增路由放行测试）；前端 test（25 项）、typecheck、build、format:check 与 9 项 Playwright 通过。
- IAM 与 demo-service 分别以真实 Token 声明和模拟同构 Token 验证，未跑三服务真链路。
- 双轴审查后修复：切换进行中遇到 401 的旧租户请求不再带新租户 Token 重发；选择租户被拒绝时返回 `auth.tenantAccessDenied` 并留在选择页；切换遇到非 401 故障时保留原会话（`auth.switchFailed`）。切换后旧租户的 Access Token 在过期前仍可离线验签，即时吊销由工单 07 处理。
