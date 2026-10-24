# Tenant Realm 最小登录

工单：[03](../.scratch/saas-forge-mvp/issues/03-tenant-realm-minimal-login.md)。Identity 是全局身份，Membership 绑定 Identity 与 Tenant；首版只自动进入唯一的有效 Membership，多租户选择留给工单 06。

## 本地运行

按 README 启动依赖和构建后，在**仓库根目录**启动 IAM 并显式启用演示数据：

```bash
java -jar backend/forge-iam/target/forge-iam-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

`demo` profile 在普通迁移之外加载 `db/demo` 的 Flyway 演示数据。仅用于独立本地数据库，不要在共享环境启用。账号 `demo`，邮箱 `demo@example.com`，密码 `Demo-password-123!`。不启用此 profile 时只创建表，不插入已知密码的身份。

按 README 启动 demo-service 和网关，然后在 `frontend` 运行 `pnpm dev:tenant`，访问 <http://localhost:5174>。登录区展示当前身份、租户，以及 demo-service 验签后返回的三个 ID。原有子应用技术验证区域仍保留。

Vite 将 `/iam` 与 `/demo` 代理到本地网关 `http://localhost:8080`。部署前端时需由同源反向代理提供这两个路径。

## 接口与验证边界

| 接口（网关路径） | 请求 | 结果 |
| --- | --- | --- |
| `POST /iam/auth/login` | JSON `account`、`password` | Redis 登录会话 Cookie、`clientId`、`redirectUri` |
| `GET /iam/oauth2/authorize` | 标准 authorization_code + S256 PKCE 参数 | 重定向至注册回调，携带一次性 code 和 state |
| `POST /iam/auth/exchange` | JSON `code`、`codeVerifier` | 服务端经标准 Token 端点交换，返回 `accessToken`、`refreshToken`、`tokenType`、`expiresIn`、`identityName`、`tenantName` |
| `POST /iam/oauth2/token` | 标准表单 + 服务端 client authentication | 标准 OAuth Token 响应 |
| `POST /iam/auth/refresh` | JSON `refreshToken` | 新的一对 Token，原 Refresh Token 立即不可重用 |
| `GET /iam/oauth2/jwks` | 无 | Spring Authorization Server 发布的公钥 |
| `GET /demo/context` | `Authorization: Bearer …` | `identityId`、`tenantId`、`membershipId`、`version` |

登录使用标准 OAuth2.1 授权码 + S256 PKCE：JSON 凭证入口只建立登录会话；SDK 生成 verifier 和 state，经 `/oauth2/authorize` 获取授权码，并校验回调 state。`/auth/exchange` 与 `/auth/refresh` 是持有客户端密钥的同进程适配器，向固定回环地址的 `/oauth2/token` 发送标准授权码或刷新请求，所有校验、签发、轮换由 Spring Authorization Server 完成。浏览器不持有 client secret；没有启用 password grant。首版 scope 为 `tenant`，不签发 OIDC ID Token。

Access Token 有效期 15 分钟，包含 `sub`、`tenant_id`、`membership_id`、`ver`，audience 为 `tenant-app`。网关和 SDK 都检查签名、issuer、有效期、audience 和必要上下文；客户端租户请求头不能覆盖 JWT。平台业务路由当前拒绝 Tenant Token，Platform Realm 在工单 08 接入。

Redis 保存 Spring Session 登录会话和标准 `OAuth2Authorization`，刷新凭证有效期 7 天。授权码/Token 的索引使用 SHA-256；认证前的索引和会话统一使用 `iam:global:` 前缀，因查询前还没有可相信的租户标识。授权状态采用 Spring 的 JDK 序列化器，只连接受信任的 Redis。Token 端点使用 Redis 互斥避免同一凭证被并发消费，标准提供者负责授权码失效和刷新轮换。刷新时重新查询原会话绑定的身份、租户和 Membership，不能隐式切换租户。Redis 故障时登录或刷新失败。工单 07 的 Access Token 即时吊销尚未接入；刷新失败后前端清除本地凭证。

`@forge/sdk` 的 `createTenantClient(sessionStorage)` 提供授权码 + PKCE 登录、刷新、同源请求和会话读取。Token 保存在当前标签页的 sessionStorage；请求遇到 401 时合并并发刷新并只重试一次。生产环境必须使用 HTTPS，并保持前端内容安全，防止脚本读取浏览器凭证。「重新登录」清除本地凭证，未提供服务端退出/吊销接口。

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `forge.security.issuer` | `http://localhost:8080/iam` | IAM、网关、业务服务必须相同 |
| `forge.security.jwk-set-uri` | `http://localhost:8101/oauth2/jwks` | 网关和业务服务的 IAM 内部地址，公钥缓存后本地验签 |
| `forge.iam.redirect-uri` | `http://localhost:5174/` | 必须与 Shell 同源；改为 127.0.0.1 访问时同步调整 |
| `forge.iam.client-secret` | 每次启动随机生成 | 仅服务端交换适配器持有；多实例使用同一外部配置值 |
| `forge.iam.signing-key-file` | `deploy/data/iam/signing.jwk` | IAM 首次启动生成 RSA 私钥文件，权限 0600；后续启动复用，禁止提交 |
| `FORGE_REDIS_HOST` / `FORGE_REDIS_PORT` | `127.0.0.1` / `6379` | IAM 会话 Redis |

变更端口或域名时同步调整 issuer、JWKS、注册回调 URI 和代理目标。多实例 IAM 应提供同一受保护密钥；本工单不提供密钥轮换管理。

## 验收命令

后端：`cd backend && ./mvnw verify`。IAM 测试使用临时 PostgreSQL、Redis 容器及独立签名文件；网关与 demo-service 测试经真实 HTTP/JWKS 验签，不替换 JwtDecoder。前端：`cd frontend && pnpm test && pnpm typecheck && pnpm build && pnpm format:check`。数据库迁移测试与 IAM 测试需要 Docker。
