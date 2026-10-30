# Platform Realm 与运营登录

工单：[08](../.scratch/saas-forge-mvp/issues/08-platform-realm-operator-login.md)，决策见 [ADR-0003](adr/0003-iam-dual-realm.md)。Operator 账号独立于 Identity，由同一个 `forge-iam` 以独立 issuer 和独立签名密钥签发 Token；网关按路由只接受对应 Realm 的 Token。登录流程、刷新轮换、会话与 Redis 存储与 Tenant Realm 相同，见 [Tenant Realm 最小登录](tenant-login.md)，本文只写差异。

## 本地运行

`demo` profile 额外插入演示 Operator：账号 `operator`，密码 `Operator-password-123!`（`db/demo/R__demo_operator.sql`）。按 [tenant-login.md](tenant-login.md) 启动 IAM（demo profile）、网关，再启动 forge-platform，然后在 `frontend` 运行 `pnpm dev:platform`，访问 <http://localhost:5173>。登录后显示当前 Operator 名称，以及 forge-platform 经 SDK 验签后返回的 Operator ID。Vite 将 `/iam` 与 `/platform` 代理到本地网关。

## 两个 Realm 的区别

| | Tenant Realm | Platform Realm |
| --- | --- | --- |
| 账号表 | `identity`（+ `membership`） | `operator`（V4 迁移，无 `tenant_id`、无 RLS） |
| 登录标识 | 账号或邮箱 | 仅账号 |
| 注册客户端 / scope | `tenant-app` / `tenant` | `platform-console` / `platform` |
| issuer | `forge.security.issuer`（`http://localhost:8080/iam`） | `forge.security.platform.issuer`（`http://localhost:8080/iam/platform`） |
| 签名密钥 | `forge.iam.signing-key-file` | `forge.iam.platform.signing-key-file`（`deploy/data/iam/platform-signing.jwk`） |
| JWKS | `/iam/oauth2/jwks` | `/iam/platform/oauth2/jwks` |
| OIDC 元数据 | `/iam/.well-known/openid-configuration` | `/iam/platform/.well-known/openid-configuration` |
| Access Token claim | `sub`（Identity）、`tenant_id`、`membership_id`、`ver`，`aud=tenant-app` | `sub`（Operator）、`ver`，`aud=platform-console` |
| 网关路由 | `/demo/**`、`/iam/auth/memberships` 等 | `/platform/**`、`/iam/platform/**`（登录相关接口匿名） |

两个 Realm 共用 IAM 的 `/oauth2/authorize` 与内部 `/oauth2/token`，元数据中的 `authorization_endpoint`、`token_endpoint` 因此相同；以 issuer、JWKS 与 scope 区分。首版不签发 ID Token，元数据的 `scopes_supported` 不含 `openid`；`id_token_signing_alg_values_supported` 等字段是 OIDC 发现文档的必填项，只说明将来签发 ID Token 时的算法。`token_endpoint` 指向的 `/iam/oauth2/token` 不经网关开放，浏览器经 `exchange`/`refresh` 适配器换发 Token。

## 接口（网关路径）

| 接口 | 请求 | 结果 |
| --- | --- | --- |
| `POST /iam/platform/auth/login` | JSON `account`、`password` | IAM 登录会话 Cookie、`clientId`、`redirectUri` |
| `GET /iam/oauth2/authorize` | `client_id=platform-console`、`scope=platform`、S256 PKCE | 重定向至 platform-console 回调 |
| `POST /iam/platform/auth/exchange` | JSON `code`、`codeVerifier` | `accessToken`、`refreshToken`、`tokenType`、`expiresIn`、`operatorName` |
| `POST /iam/platform/auth/refresh` | JSON `refreshToken` | 新的一对 Token，轮换与重放撤销同 Tenant Realm |
| `POST /iam/platform/auth/logout` | 同源 JSON `{}` + IAM 会话 Cookie | 销毁 IAM 登录会话 |
| `GET /platform/operators/current` | `Authorization: Bearer <Platform Token>` | forge-platform 经 SDK 读取的 `operatorId` |

`/iam/platform/auth/*` 同样一律 `Cache-Control: no-store`。账号不存在、密码错误、账号属于另一个 Realm 都返回同一个 401 `auth.failed`。

## 隔离边界

- **签发**：Token 定制器按客户端决定 Realm，并要求登录会话的主体属于同一 Realm（`TENANT` / `OPERATOR` 权限），否则以 `invalid_grant` 拒绝。同一浏览器的 IAM 登录会话可以向任一客户端申请授权码，但换码时被拒（401 `auth.failed`）。
- **密钥**：`JwtEncoder` 按 issuer 选择签名密钥。Platform 公钥只由 `/platform/oauth2/jwks` 发布，不出现在标准 `/oauth2/jwks` 中，所以即使某个验签方漏查 issuer，也无法用对方 JWKS 验过。
- **网关**：`/platform/**` 与 `/iam/platform/**` 用 Platform 解码器（issuer、`aud=platform-console`、`sub` 为 UUID、`ver` 为数字）加 Operator 版本校验，其余受保护路由用 Tenant 解码器加即时失效校验。用错 Realm 的 Token 一律 401 `auth.unauthenticated`（`invalid_token`），不是 403。
- **业务服务 SDK**：`forge.security.realm` 取 `tenant`（默认）或 `platform`，其他取值启动失败。`platform` 时用 `forge.security.platform.issuer` 与 `forge.security.platform.jwk-set-uri` 验签，不绑定 `CurrentTenant`，以 `OperatorContext.current()` 读取当前 Operator。forge-platform 已配置为 `platform`。
- **前端 SDK**：`createTenantClient(storage)` 与 `createPlatformClient(storage)` 分别使用 `tenantRealm`、`platformRealm` 配置（接口前缀、scope、存储键 `forge.tenant.session` / `forge.platform.session`、跨标签页轮换锁 `forge.tenant.rotation` / `forge.platform.rotation`）。platform-console 通过 `platformClientKey` 提供唯一实例，会话存于 `localStorage`，同一浏览器的标签页共享登录：其他标签页刷新时按新 Token 重新读取权限，登录或退出时登录面板随之切换，刷新跨标签页互斥，见 [ADR-0017](adr/0017-local-storage-session-cross-tab.md)。

## 已知限制

- 两个 Realm 共用一个 IAM 登录会话 Cookie。同一浏览器先登录一个 Realm、再登录另一个时，后者替换 IAM 会话：已签发的 Token 不受影响，但前者失去免密重新授权（SSO）。
- Operator 的平台角色、超级管理员、账号管理与即时失效见 [平台侧权限](platform-permission.md)（工单 22）。

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `forge.security.platform.issuer` | 无，本地值 `http://localhost:8080/iam/platform` 写在各服务 `application.yml` | IAM、网关、forge-platform 必须相同，漏配则启动失败；不能与 `forge.security.issuer` 相同，否则 IAM 拒绝启动 |
| `forge.security.platform.jwk-set-uri` | 无，本地值 `http://localhost:8101/platform/oauth2/jwks` | 网关和 forge-platform 访问 IAM 的内部地址 |
| `forge.security.realm` | `tenant` | 业务服务接受哪个 Realm 的 Token；forge-platform 为 `platform` |
| `forge.iam.platform.redirect-uri` | `http://localhost:5173/` | platform-console 回调，必须与之同源 |
| `forge.iam.platform.signing-key-file` | 无，本地值 `deploy/data/iam/platform-signing.jwk` 写在 IAM `application.yml` | 首次启动生成，权限 0600，禁止提交；多实例须共用同一密钥，漏配则启动失败 |
| `forge.iam.platform.mfa-required` | `false` | 是否强制 Operator 使用 MFA。第一期未实现 MFA，设为 `true` 时所有 Operator 登录在校验密码前以 403 `auth.mfaRequired` 拒绝；已有的 IAM 会话授权与 Refresh Token 刷新也被拒绝（401 `auth.failed`），不会退化为仅凭密码登录。已签发的 Access Token 在过期前仍有效 |

`forge.iam.client-secret` 由两个注册客户端共用。

## 验收

后端 `cd backend && ./mvnw verify`：`PlatformLoginTest`（Operator 登录、独立 issuer 与密钥、跨 Realm 签发被拒、元数据）、`OperatorSessionServiceTest`（MFA 开关）、`GatewaySecurityTest`（Tenant Token 访问平台路由与 Platform Token 访问租户路由均 401）、forge-platform `OperatorContextTest`（SDK 验签并读取 Operator）。前端 `pnpm test` 中 platform-console 的 `LoginPanel.spec.ts`。
