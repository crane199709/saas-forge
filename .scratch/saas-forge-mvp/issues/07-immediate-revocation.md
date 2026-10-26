# 07: Token 即时失效与刷新

**What to build:** 停用 Membership、修改其角色或停用租户后，用户的下一次请求立即按新状态处理，而不是等 Access Token 过期（ADR-0005）。前端遇到失效时自动用 Refresh Token 换新，换不到则登出。

**Blocked by:** 06 多租户成员与切换租户

**Status:** ready-for-agent

- [ ] iam 在 Redis 维护每个 Membership 的 `version` 与每个租户的状态，相关变更时更新
- [ ] 网关每次请求比对 Token 中的 `ver` 与租户状态，不一致返回 401
- [ ] Refresh Token 轮换：每次刷新签发新 Refresh Token，旧的立即失效；重放旧 Refresh Token 使该会话失效
- [ ] Membership 已停用或租户已停用时刷新失败
- [ ] 角色与权限明细不放入 JWT；SDK 按 `membership_id + version` 从 Redis 读取并本地缓存，version 变化后缓存失效
- [ ] `@forge/sdk` 拦截 401 自动刷新并重放请求，刷新失败跳转登录；并发请求只触发一次刷新
- [ ] 测试：停用 Membership 后下一次请求被拒；递增 version 后旧 Token 被拒、刷新后的新 Token 可用


## Comments

2026-10-26 后续评估：当前刷新轮换为每张已消费凭证保留一个 `:used-refresh` 摘要键，直到旧凭证原始过期时间（最长约 7 天）。持续活跃或频繁刷新的授权会积累历史键；本工单评估按授权聚合存储、容量与过期清理，保持旧凭证重放可识别。无宽限期下，复制标签页共享凭证、刷新响应丢失及独立客户端并发刷新可能导致整条授权失去刷新能力，详见 [登录说明](../../../docs/tenant-login.md)。
