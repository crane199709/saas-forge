# 07: Token 即时失效与刷新

**What to build:** 停用 Membership、修改其角色或停用租户后，用户的下一次请求立即按新状态处理，而不是等 Access Token 过期（ADR-0005）。前端遇到失效时自动用 Refresh Token 换新，换不到则登出。

**Blocked by:** 06 多租户成员与切换租户

**Status:** resolved

- [x] iam 在 Redis 维护每个 Membership 的 `version` 与每个租户的状态，相关变更时更新
- [x] 网关每次请求比对 Token 中的 `ver` 与租户状态，不一致返回 401
- [x] Refresh Token 轮换：每次刷新签发新 Refresh Token，旧的立即失效；重放旧 Refresh Token 使该会话失效
- [x] Membership 已停用或租户已停用时刷新失败
- [x] 角色与权限明细不放入 JWT；SDK 按 `membership_id + version` 从 Redis 读取并本地缓存，version 变化后缓存失效
- [x] `@forge/sdk` 拦截 401 自动刷新并重放请求，刷新失败跳转登录；并发请求只触发一次刷新
- [x] 测试：停用 Membership 后下一次请求被拒；递增 version 后旧 Token 被拒、刷新后的新 Token 可用


## Comments

2026-10-26 后续评估：当前刷新轮换为每张已消费凭证保留一个 `:used-refresh` 摘要键，直到旧凭证原始过期时间（最长约 7 天）。持续活跃或频繁刷新的授权会积累历史键；本工单评估按授权聚合存储、容量与过期清理，保持旧凭证重放可识别。无宽限期下，复制标签页共享凭证、刷新响应丢失及独立客户端并发刷新可能导致整条授权失去刷新能力，详见 [登录说明](../../../docs/tenant-login.md)。

2026-10-26 实现与验收：
- iam 新增 `MembershipStateService`（停用、递增版本）与 `TenantStateService`（修改状态），先提交数据库再写 Redis；签发 Token 时按数据库补发版本、租户状态与角色权限明细，Redis 丢失状态可随刷新恢复。版本只前进、租户状态签发时只补写缺失键，避免并发签发覆盖变更。键约定在 SDK `MembershipStateKeys`。管理接口留给 15、16、17。
- 网关验签后一次 `MGET` 比对 `ver` 与租户状态，不一致或缺失返回 401，Redis 不可用返回 503（经完整安全过滤链测试）。
- SDK `MembershipGrantStore` 按 `membership_id + ver` 读取明细并按 Membership 本地缓存；角色管理（16）前明细为空。
- 刷新轮换与重放撤销沿用工单 03 的实现，新增停用 Membership、停用租户后刷新失败及版本递增后刷新获得新 `ver` 的测试。`:used-refresh` 聚合评估结论：需改写 Refresh Token 格式且内存量级不变，保持现状，记录在登录说明。
- 前端 SDK 的 401 合并刷新与重放已有实现，新增并发只刷新一次的测试；刷新凭证被拒绝才清除会话，刷新接口 5xx 或网络故障保留会话（评审发现：签发依赖 Redis 后，原实现会在 Redis 故障时把用户登出）；会话被清除后登录面板回到登录表单、Shell 回到 `/`。
- 验证：后端 `./mvnw verify` 通过；前端 test（29 项）、typecheck、build、format:check 通过。网关与 iam 分别测试（网关以测试写入的 Redis 状态模拟 iam 发布），未跑三服务真链路；Playwright e2e 未运行。

