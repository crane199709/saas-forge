# 03: Tenant Realm 最小登录链路

**What to build:** 打通认证的最细一条路径：一个预置的 Identity（属于一个预置 Tenant、持有一个 Membership）在 tenant-app 用账号密码登录，iam（基于 Spring Security 内置 Authorization Server，ADR-0004）签发 Access Token 与 Refresh Token；请求经网关校验 Tenant Realm issuer 后到达 demo-service，demo-service 经 SDK 用 JWKS 本地验签，返回当前 Identity、Tenant、Membership；tenant-app 显示当前用户与租户。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** ready-for-agent

- [ ] iam 以 Flyway 迁移建立 Identity、Tenant（最小字段）、Membership 的表结构与预置数据
- [ ] 账号/邮箱 + 密码登录成功，Access Token（约 15 分钟）claims 含 `sub`、`tenant_id`、`membership_id`、`ver`
- [ ] 错误密码、未知账号返回统一的认证失败响应
- [ ] Refresh Token 可换取新的 Access Token
- [ ] 网关拒绝无 Token、伪造签名或 issuer 不符的请求
- [ ] demo-service 引入 SDK 安全 starter 后无需手写配置即可验签，并能在业务代码中读取当前上下文
- [ ] `@forge/sdk` 提供登录、Token 存储、请求自动携带 Token；tenant-app 有登录页并显示当前用户与租户
- [ ] 集成测试覆盖登录成功、失败与下游验签
