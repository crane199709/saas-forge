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
| `POST /iam/auth/logout` | 同源 JSON `{}` + IAM 会话 Cookie | 销毁当前浏览器的 Redis 登录会话，返回 204 |
| `POST /iam/auth/refresh` | JSON `refreshToken` | 新的一对 Token；旧 Refresh Token 重放会撤销同一授权的后续刷新能力 |
| `GET /iam/oauth2/jwks` | 无 | Spring Authorization Server 发布的公钥 |
| `GET /demo/context` | `Authorization: Bearer …` | `identityId`、`tenantId`、`membershipId`、`version` |

登录使用标准授权码 + S256 PKCE：JSON 凭证入口建立登录会话；SDK 生成 verifier 和 state，经 `/oauth2/authorize` 获取授权码，并校验回调 state。首版 scope 为 `tenant`，不签发 OIDC ID Token，也不启用 password grant。

`/auth/exchange` 与 `/auth/refresh` 是匿名开放的 Token 交换适配器，持有服务端 client secret 并调用 IAM 内部 `/oauth2/token`。标准端点负责 PKCE 校验、Token 签发与刷新轮换，网关不再开放 `/iam/oauth2/token`。部署时 IAM 监听端口只应供内部服务访问。

**适配器没有认证浏览器客户端。** 任何持有 code + verifier 或 Refresh Token 的调用者都可以通过适配器换取 Token。因此浏览器侧仍按公开客户端管理风险；“浏览器不持有 client secret”只说明密钥存放位置，不能防止浏览器凭证被窃取后使用。这也不是 BFF：Token 仍交给浏览器保存。使用适配器是为了保留框架默认 Refresh Token 签发流程；已核对本机 Spring Security 7.0.8 的 `OAuth2RefreshTokenGenerator`，授权码流程使用 `ClientAuthenticationMethod.NONE` 时默认返回 null。

Access Token 有效期 15 分钟，包含 `sub`、`tenant_id`、`membership_id`、`ver`，audience 为 `tenant-app`。网关和 SDK 都检查签名、issuer、有效期、audience 和必要上下文；客户端租户请求头不能覆盖 JWT。平台业务路由当前拒绝 Tenant Token，Platform Realm 在工单 08 接入。

Redis 保存 Spring Session 登录会话和标准 `OAuth2Authorization`，刷新凭证有效期 7 天。授权码/Token 的索引使用 SHA-256；认证前的索引和会话统一使用 `iam:global:` 前缀，因查询前还没有可相信的租户标识。授权状态采用 Spring 的 JDK 序列化器，只连接受信任的 Redis。Token 端点按授权 ID 使用 Redis 互斥，避免同一授权的凭证并发消费。重放撤销不等待这把锁；在途换码或刷新只能原子更新仍然存在的授权，不能重新创建被撤销的授权。标准提供者负责授权码失效和刷新轮换；旧 Refresh Token 的摘要保留至其原始过期时间，检测到重放就删除该授权及当前 Token 索引，使其后续刷新失败。这也意味着丢失刷新响应后重试旧凭证会要求重新登录。刷新时重新查询原会话绑定的身份、租户和 Membership，不能隐式切换租户。Redis 故障时登录或刷新失败。工单 07 的 Access Token 即时吊销尚未接入；刷新失败后前端清除本地凭证。

当前重放检测**没有宽限期**。复制标签页时，若浏览器同时复制了 sessionStorage，两个标签页就会持有相同的 Refresh Token；一个标签页轮换后，另一个使用旧凭证会撤销整条授权的后续刷新能力。两个独立客户端并发刷新时，后到的请求若已被识别为使用旧凭证，即使遇到锁争用，也会触发撤销。两边都需要重新登录，但持有的 Access Token 在过期前仍可能有效，界面不一定立即同步退出。Shell 的共享实例只能合并同一实例内正在进行的刷新，不能协调复制出的标签页。暂不加入宽限期，以保持旧凭证一经识别为重放即撤销的语义。

每次轮换保留一个 `:used-refresh` 摘要键，TTL 截止该旧凭证原来的过期时间，最长约 7 天；撤销授权后历史摘要也按各自 TTL 自然过期。按每 15 分钟刷新一次、连续活跃 7 天估算，一条授权约积累 672 个历史键；主动调用刷新接口会更多，因此这不是数量上限。运维需结合实际刷新频率关注键数、TTL、Redis 内存和淘汰情况，不能通过提前删除摘要来降低内存占用，否则会丢失重放关联。工单 07 评估按授权聚合存储及过期清理；本次保持现有存储结构。

`@forge/sdk` 的 `createTenantClient(sessionStorage)` 提供登录、退出、刷新、同源请求和会话读取。Shell 的 `App.vue` 创建唯一实例并 `provide(tenantClientKey, client)`；LoginPanel 与后续 Sub-App 通过 `inject(tenantClientKey)` 获取同一实例，不再自行创建客户端。注入键携带 `InjectionKey<TenantClient>` 类型信息。并发刷新只在这个共享实例内合并；不同标签页不共享刷新队列。demo-service `/context` 的响应类型由 tenant-app 调用方维护，不由通用 SDK 导出。

Token 保存在当前标签页的 sessionStorage。请求遇到 401 时合并刷新并只重试一次。生产环境必须使用 HTTPS，并防止脚本读取浏览器凭证。

「重新登录」调用 `/auth/logout`，清除本地凭证并等待服务端销毁 IAM 会话；失败时显示“浏览器仍可能保持登录”并允许重试。此接口只接受 JSON，不启用跨域访问。IAM 登录会话的空闲超时为 30 分钟（访问可延长会话）；未退出时，同一浏览器可用新 PKCE 再次授权而不输入密码，这是保留的 SSO 行为。**关闭标签页或单独调用 SDK `clear()` 不会退出 IAM 会话**。退出当前浏览器会话不撤销已签发的 Access/Refresh Token，也不清除其他标签页的凭证；已泄漏 Access Token 在过期前仍可通过离线验签，即时吊销由工单 07 接入。

账号区分大小写，邮箱按不区分大小写匹配并保持唯一；账号禁止 `@`，邮箱必须包含非空的 `@` 两侧，避免两个登录标识命名空间重叠。V3 迁移只增加约束，不自动改写历史数据；已有非法邮箱或仅大小写不同的重复邮箱会使迁移失败，需要先由维护者处理。

多 Membership 身份只有在密码正确后才返回 `409 / auth.membershipSelectionRequired`，提示当前版本尚不支持租户选择；错误密码和未知账号继续返回同一 401。选择功能仍由工单 06 实现。IAM 认证阶段 Membership 查询的 RLS 例外及边界见 [ADR-0001](adr/0001-shared-schema-tenant-isolation.md)。

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `forge.security.issuer` | `http://localhost:8080/iam` | IAM、网关、业务服务必须相同 |
| `forge.security.jwk-set-uri` | `http://localhost:8101/oauth2/jwks` | 网关和业务服务的 IAM 内部地址，公钥缓存后本地验签 |
| `forge.iam.redirect-uri` | `http://localhost:5174/` | 必须与 Shell 同源；改为 127.0.0.1 访问时同步调整 |
| `forge.iam.client-secret` | 每次启动随机生成 | 服务端适配器持有；多实例需一致；不构成浏览器客户端认证 |
| `forge.iam.token-base-uri` | 按 IAM 实际监听地址、端口、TLS 和 context-path 推导 | 可显式配置内部基址（含 context-path、不含 `/oauth2/token`）；HTTPS 须通过 JVM 信任链和主机名验证 |
| `forge.iam.signing-key-file` | `deploy/data/iam/signing.jwk` | IAM 首次启动生成 RSA 私钥文件，权限 0600；后续启动复用，禁止提交 |
| `FORGE_REDIS_HOST` / `FORGE_REDIS_PORT` | `127.0.0.1` / `6379` | IAM 会话 Redis |

变更端口或域名时同步调整 issuer、JWKS、注册回调 URI 和代理目标。多实例 IAM 应提供同一受保护密钥；本工单不提供密钥轮换管理。

## 验收命令

后端：`cd backend && ./mvnw verify`。IAM 测试使用临时 PostgreSQL、Redis 容器及独立签名文件；网关与 demo-service 测试经真实 HTTP/JWKS 验签，不替换 JwtDecoder。前端：`cd frontend && pnpm test && pnpm typecheck && pnpm build && pnpm format:check`。数据库迁移测试与 IAM 测试需要 Docker。


IPv6 回归（运行环境需支持 `::1`）：

```bash
cd backend
./mvnw -pl forge-iam -am '-Dtest=TenantLoginTest#emailLoginAndJwksVerifyRequiredClaims+refreshReplayRevokesTheAuthorizationFamily' -Dsurefire.failIfNoSpecifiedTests=false -Dforge.test.iam-bind-address=::1 test
```

该回归让 IAM 实际绑定 IPv6 回环地址，覆盖非默认 context-path 下的换码、JWKS 验签与刷新，不设置 `forge.iam.token-base-uri` 覆盖值。显式配置 IPv6 基址时同样需要方括号，例如 `http://[::1]:8101`。

若 JVM 配置了 HTTP 代理，还需保证实际内部地址直连。`::1` 经 Java 展开后为 `0:0:0:0:0:0:0:1`，不能只验证压缩形式的代理规则。本机隔离回归在依赖已缓存的情况下，额外传入 `-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost= '-Dhttp.nonProxyHosts=*'` 临时排除代理影响；这些参数只用于当前本地测试，不修改机器配置，也不作为生产配置建议。
