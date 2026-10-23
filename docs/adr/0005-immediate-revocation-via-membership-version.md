# 权限明细不进 JWT，靠 Membership version 实现即时失效

Access Token 有效期约 15 分钟，但停用 Membership、停用租户或修改角色必须立即生效。iam 在 Redis 维护每个 Membership 的 `version` 与租户状态，网关每次请求比对 Token 中的 `ver` 与租户状态（一次 Redis 读），不一致返回 401，前端用 Refresh Token 自动换新，失败则登出。角色与权限明细不放进 JWT，SDK 按 `membership_id + version` 从 Redis 拉取并本地缓存。这意味着网关对 Redis 有强依赖，这是为即时失效付出的有意代价。
