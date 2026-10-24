# 03: Tenant Realm 最小登录链路

**What to build:** 打通认证的最细一条路径：一个预置的 Identity（属于一个预置 Tenant、持有一个 Membership）在 tenant-app 用账号密码登录，iam（基于 Spring Security 内置 Authorization Server，ADR-0004）签发 Access Token 与 Refresh Token；请求经网关校验 Tenant Realm issuer 后到达 demo-service，demo-service 经 SDK 用 JWKS 本地验签，返回当前 Identity、Tenant、Membership；tenant-app 显示当前用户与租户。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** ready-for-agent

- [x] iam 以 Flyway 迁移建立 Identity、Tenant（最小字段）、Membership 的表结构与预置数据
- [x] 账号/邮箱 + 密码登录成功，Access Token（约 15 分钟）claims 含 `sub`、`tenant_id`、`membership_id`、`ver`
- [x] 错误密码、未知账号返回统一的认证失败响应
- [x] Refresh Token 可换取新的 Access Token
- [x] 网关拒绝无 Token、伪造签名或 issuer 不符的请求
- [x] demo-service 引入 SDK 安全 starter 后无需手写配置即可验签，并能在业务代码中读取当前上下文
- [x] `@forge/sdk` 提供登录、Token 存储、请求自动携带 Token；tenant-app 有登录页并显示当前用户与租户
- [x] 集成测试覆盖登录成功、失败与下游验签


## Comments

2026-10-24 实现与验收：
- 登录采用标准 OAuth2.1 authorization_code + S256 PKCE；Spring Authorization Server 负责授权码、Token 签发与 refresh_token 轮换，服务端交换适配器持有 client secret，浏览器不持有客户端密钥。
- Identity 保持全局模型，通过 Membership 绑定 Tenant；预置账号由显式 `demo` profile 的 Flyway 脚本创建。启动步骤与接口见 [登录说明](../../../docs/tenant-login.md)。
- Redis 保存登录会话与标准授权状态；网关和安全 starter 分别通过 JWKS 校验签名、issuer、过期时间及上下文。刷新凭证并发消费最多一次成功。
- 后端先执行 `./mvnw clean verify`，审查修复后再次 `./mvnw verify`：最终 16 项测试通过；前端 10 项测试、typecheck、build、format:check 通过。
- Playwright 8 项通过，包含登录页与原有子应用回归。因本机 5175 已占用，在临时副本中将预览端口与对应 CORS 白名单映射到 15174–15176 执行，仓库配置保持原端口。
- 隔离 PostgreSQL/Redis + 打包 IAM、网关、demo-service 真链路通过：账号/邮箱登录、统一失败、下游验签、刷新轮换/重放拒绝、IAM 重启后的密钥和会话持续；已清理临时服务和容器。
- Access Token 即时吊销、多 Membership 选择与 Platform Realm 分别由后续工单处理。
- 规范与规格双轴审查均已复核通过；已补齐 URL 规范化、旧刷新响应隔离、Token 类型混淆与授权码互斥回归。
