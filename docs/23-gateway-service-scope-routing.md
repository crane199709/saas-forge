# Gateway 通用路由目录与 User/Service Token Scope 策略

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。

**实施 Issue：[#75](https://github.com/crane199709/saas-forge/issues/75)**

本规格交付 Gateway 平台级通用路由机制、User/Service Token 路由策略和最小 HTTP 认证 Starter。它不交付任何 Permission、Feature 或 Quota Runtime 业务 operation。架构取舍见 [ADR 0034](adr/0034-controlled-service-registry-and-route-catalog.md)，领域所有权见 [Context Map](../CONTEXT-MAP.md)。

## 1. 当前事实与差距

当前正式 REST 输入是唯一根契约 `saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml`，Gateway 在 Maven `generate-resources` 阶段直接生成五列 TSV：

```text
operationId  method  path  target  userTokenRequirement
```

当前实现存在以下已验证差距：

- 生成器通过文本行和缩进解析 YAML，不解析 OpenAPI 语义或 `$ref`；
- owner switch、运行时 `Target` 枚举和质量门分别硬编码 IAM、Tenant Access、Entitlement；
- 认证只有 `NONE / OPTIONAL / REQUIRED` 三种 User Token 分类；
- 未知 Security Scheme 会被误归为 `NONE`；
- 没有格式版本、method/path 规范化冲突和 serviceId 注册门禁；
- Gateway 没有 Service Token 验签、Scope、`client_id` 吊销检查；
- Starter 当前只是聚合 POM，没有 HTTP 接收安全实现；
- Gateway 不注入上下文 Header，但也没有删除客户端伪造的身份、Tenant、Role、Permission 或 Scope Header；
- 无健康实例的专项测试使用内存发现器，尚需真实 Nacos 验收。

当前正式 OpenAPI 有 Platform Entitlement Bootstrap 操作，但没有 Permission、Feature、Quota Runtime operation。不得将前者或测试路由宣称为生产 Runtime Service route。

## 2. 交付边界

本切片交付：

- 受版本控制的 Service Registry 与 OAuth Scope Registry；
- 语义级 OpenAPI 解析和构建门禁；
- 可发布、带版本、不可变的共享 HTTP Route Catalog；
- IAM、Tenant Access、Entitlement 迁移到相同 `serviceId` 路由机制；
- Gateway User/Service Token 互斥验证与多 Scope AND 校验；
- 最小生产可用 HTTP 认证 Starter；
- 不可信上下文 Header 防护；
- 非生产真实接收端、真实 IAM/Redis/Nacos 的 Compose 验收；
- 现有 User Token、Cookie、Basic、404、405、Trace 与上游错误行为回归证明。

本切片不交付：

- Permission、Feature、Quota Runtime领域能力或生产 operation；
- 动态公网路由控制面；
- Nacos热更新公开路由、认证、CORS或 TLS边界；
- 同一 operation 同时接受 User和 Service Token；
- Gateway内 Platform Role、Tenant Role、Permission或资源级授权；
- Service Token的 User Tenant Context或用户冒充；
- 完整 `sdk-core`、Permission/Feature/Quota SDK、Example业务服务；
- 新 Authorization Server框架。

## 3. 当前与目标调用链

当前链路：

```text
saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml
  → 文本行生成器
  → owner switch → Target enum
  → 五列 TSV
  → Gateway User Token Filter
  → lb://{硬编码 serviceId}
  → 下游各自实现认证
```

目标链路：

```text
正式 OpenAPI operation
  + Service Registry
  + OAuth Scope Registry
  → swagger-parser-v3 语义解析与交叉校验
  → saas-forge-http-route-catalog 制品
  → Gateway 选择唯一认证器
  → User 或 Service Token 严格验证
  → lb://{serviceId}
  → Nacos 发现健康实例
  → 下游 Starter 从同一 Catalog 复验
  → 不可变 User 或 Service Authentication Context
```

新服务扩缩容和故障切换只依赖 Nacos实例收敛；新增公开 operation仍必须修改正式 OpenAPI、重新生成、构建并滚动部署 Gateway和接收服务。

## 4. Service Registry

新增：

```text
saas-forge-contracts/services/engineering-registry.json
saas-forge-contracts/services/engineering-registry.schema.json
```

Registry 顶层包含 `registryVersion` 和 `entries`。每个 entry 只包含：

| 字段 | 规则 |
|---|---|
| `serviceId` | `[a-z][a-z0-9-]*`，全局唯一 |
| `owner` | 稳定上下文所有者标识 |
| `modulePath` | 仓库相对 Maven module 路径，唯一 |
| `artifactId` | 可部署制品 ID，唯一 |
| `nacosServiceName` | 稳定 Nacos注册名，必须等于应用实际名称 |
| `deployable` | 是否为可部署应用 |
| `gatewayRouteTargetAllowed` | 是否允许正式 OpenAPI将其作为 Gateway目标 |

初始登记 `gateway`、`iam-service`、`tenant-access-service`、`entitlement-service`、`audit-service`。只有 IAM、Tenant Access、Entitlement 的 `gatewayRouteTargetAllowed=true`；Gateway和 Audit为 `false`。

质量门必须交叉验证：

- Maven module与 `artifactId`；
- `spring.application.name` 和 Nacos discovery service；
- `dev/test/staging/prod` 应用专属 Nacos资源；
- Compose与 Helm可部署清单；
- OpenAPI `x-saas.forge-service`；
- Gateway discovery ACL。

`gatewayRouteTargetAllowed=false` 的已注册服务可以没有公开 operation。值为 `true` 的服务必须至少拥有一个正式 operation。Nacos只出现实例永远不能增加 Route Catalog条目。

## 5. OAuth Scope Registry

新增：

```text
saas-forge-contracts/security/oauth-scope-registry.json
saas-forge-contracts/security/oauth-scope-registry.schema.json
```

每个 Scope 包含：

| 字段 | 规则 |
|---|---|
| `scope` | 小写、冒号分段、全局唯一，不允许通配符 |
| `ownerServiceId` | 已登记服务 |
| `clientTypes` | `RESERVED_SERVICE`、`RUNTIME_SERVICE` 的允许集合 |
| `usage` | `INTERNAL` 或 `RUNTIME` |
| `gatewayRouteAllowed` | 是否可用于公网 `SERVICE_REQUIRED` operation |
| `description` | 稳定、非授权逻辑的用途说明 |

IAM `OAuthScope`、Reserved Client固定授权、OpenAPI Scheme和 Route Catalog都必须与该 Registry一致。MVP只有 `runtime:read` 与 `runtime:quota:write` 可设 `gatewayRouteAllowed=true`；内部 `iam:*`、`tenant-access:*`、`entitlement:*` Scope不得出现在公网 Service Route。

## 6. OpenAPI Security Scheme

正式 OpenAPI保留：

- `UserBearerAuth`：User Access Token；
- `OAuthClientBasic`：`/oauth2/token` 的 Client认证。

新增：

- `PlatformRefreshCookieAuth`：`apiKey`、`in: cookie`、`name: __Host-sf_platform_refresh`；
- `TenantRefreshCookieAuth`：`apiKey`、`in: cookie`、`name: __Host-sf_tenant_refresh`；
- `ServiceOAuth2`：标准 OAuth2 `clientCredentials` flow，`tokenUrl: /oauth2/token`，scopes来自固定 Registry。

每个 operation生成一个互斥分类：

| 分类 | Gateway行为 | Receiver行为 |
|---|---|---|
| `ANONYMOUS` | 不验证 Bearer | 不要求 User/Service Token |
| `REFRESH_COOKIE_REQUIRED` | 不以 Bearer定位 | IAM验证 Refresh Cookie |
| `OAUTH_CLIENT_BASIC_REQUIRED` | 保留 Basic | IAM验证 Client Basic |
| `USER_OPTIONAL` | 无 Bearer放行；非法 Bearer仍按登出特例转发；撤销状态不可判定返回 503 | IAM清理 Cookie，仅在 Bearer有效时附加撤销 `jti` |
| `USER_REQUIRED` | 严格验证 User Token | Starter再次验证并建立 User Context |
| `SERVICE_REQUIRED` | 严格验证 Service Token和全部 required scopes | Starter再次验证并建立 Service Context |

Tenant Switch 与 Context Selection 使用 `TenantRefreshCookieAuth`，Initial Password Change 使用 `PlatformRefreshCookieAuth`；Refresh 依必填 `sessionSlot` 选择两者之一。登录、Password Setup、JWKS为 Anonymous；Token issuance使用 OAuth Client Basic；Logout保持 User Optional，并依必填 `sessionSlot` 只处理所选 Cookie；Platform/Tenant用户接口保持 User Required。双 Cookie、Origin 与 Intent/Slot 配对规则见 [ADR 0038](adr/0038-browser-sessions-use-intent-bound-slots.md)。

同一 operation不得混合 User与 Service Bearer。Service-required scopes采用 AND：Token必须包含全部 required scopes，可以包含其他已登记且该 Client合法获授的 Scope。

## 7. Route Catalog 契约

新增可发布 Maven制品 `saas-forge-http-route-catalog`，生成资源：

```json
{
  "schemaVersion": 1,
  "routes": [
    {
      "operationId": "readRuntimeCapability",
      "method": "GET",
      "path": "/api/v1/runtime/capabilities/{capabilityId}",
      "serviceId": "example-service",
      "credentialRequirement": "SERVICE_REQUIRED",
      "requiredScopes": ["runtime:read"]
    }
  ]
}
```

规则：

- JSON字段固定，未知字段拒绝；
- `schemaVersion` 必须精确为 `1`；
- routes按 method、path、operationId确定性排序；
- required scopes去重并按字典序排序；
- 生成器和读取器本切片原子升级，不保留 TSV兼容层；
- Gateway与 Starter依赖同一制品和加载器；
- Catalog为空、缺失、版本不支持、字段非法或存在冲突时应用启动失败；
- Catalog不能由 Nacos或服务发现更新。

路径冲突规范化时忽略变量名：`/items/{id}` 与 `/items/{itemId}` 在相同 method下冲突。不同 method可以共享 path。operationId全局唯一；method +规范化 path全局唯一。

## 8. 构建门禁

采用根 POM管理版本的 `swagger-parser-v3` 解析 OpenAPI 3.1与引用；子模块不得声明版本。构建必须拒绝：

- 解析错误、未解析引用或未知 Security Scheme；
- 缺 path、method、operationId、`x-saas.forge-service` 或认证元数据；
- 未登记、命名非法或不允许路由的 serviceId；
- serviceId与 module、artifact、Nacos、部署清单不一致；
- operationId重复；
- method +规范化 path重复或归属冲突；
- security alternatives产生 User/Service混用；
- Anonymous与任一 required凭据冲突；
- Service-required scopes为空、重复、无法规范化、未登记或不允许公网使用；
- Scope Registry、Service Registry或契约 entry重复；
- 允许路由的服务没有正式 operation；
- 只有 Nacos注册、没有正式 OpenAPI operation却试图产生路由。

引入 `swagger-parser-v3` 的替代方案是用 SnakeYAML自行实现 OpenAPI语义与引用解析；该方案因安全误解析和维护成本被拒绝。

## 9. Gateway 认证责任

Gateway负责：

- 依据 Catalog选择唯一 Token验证器；
- 检查 RS256、`typ`、`kid`、签名、issuer、单 audience、`iat/exp` 和精确 Claim白名单；
- User Token检查 `jti`、`kid`、Membership/Tenant Fence与 Revocation Index Ready；
- Service Token检查 `kid`、`client_id` 吊销与 Ready；
- Service Token验证全部 required scopes；
- 验证失败时停止转发；
- 原样转发合格 Authorization；
- 延续或生成 W3C Trace Context；
- 删除保留上下文 Header；
- 通过 `lb://{serviceId}` 发现健康实例。

Gateway不负责：

- Platform Role、Tenant Role、Permission或资源级授权；
- 从请求参数建立 User Tenant Context；
- 为 Service Token建立 Tenant Context；
- 注入可被下游直接信任的身份、Tenant、Role、Permission或 Scope Header；
- 业务编排；
- 因服务注册而自动公开路由。

## 10. 最小 HTTP 认证 Starter

Starter加载同一 Route Catalog，以当前 `spring.application.name` 作为 serviceId并在启动时验证匹配。每个请求按 serviceId、method、path选择本服务 operation；不能使用手写路径白名单。

Starter提供互斥、不可变的 Spring Security principal：

```text
UserAuthenticationContext(identityId, contextType, membershipId?, tenantId?)
ServiceAuthenticationContext(clientId, scopes)
```

Starter必须：

- 复验原始 User或 Service Token；
- 复用签名、Claim、Redis Revocation和 Ready组件；
- Tenant形态 User Token额外复验 Membership/Tenant Fence；
- Service Context只暴露 `clientId + scopes`；
- 不暴露 Token、`jti`、`kid` 或可写 setter；
- 在 Filter `finally` 中清理 Security Context；
- 不自动传播到 `@Async`、线程池、调度器或 Kafka Consumer；
- 为 401、403、503输出与 Gateway一致的 Problem Details；
- 禁止生产 receiver只调用底层 Signature Verifier；
- 禁止因请求来自 Gateway跳过复验。

Tenant Operation Target是正式 path/body/message契约中的业务目标，不写入认证上下文。下游仍须按 `client_id`、required Scope和资源归属校验，并在事务中显式设置数据访问目标。

## 11. 不可信上下文 Header

建立大小写不敏感的保留 Header Registry，覆盖平台命名的 Identity、Membership、Tenant Context、Role、Permission和 Scope Header。Gateway转发前删除这些 Header；Starter在直连请求仍出现保留 Header时返回：

```text
400 UNTRUSTED_CONTEXT_HEADER
```

业务代码只读取 Starter Context或正式 Tenant Operation Target。任意未登记同义 Header没有安全语义，也不得被服务读取。

## 12. Problem Details 与 WWW-Authenticate

| 情况 | HTTP | code | `WWW-Authenticate` |
|---|---:|---|---|
| Required路由缺 Token | 401 | `ACCESS_TOKEN_INVALID` | `Bearer` |
| 格式、签名、时间、Claim非法 | 401 | `ACCESS_TOKEN_INVALID` | `Bearer error="invalid_token"` |
| User路由收到 Service Token或反之 | 401 | `ACCESS_TOKEN_INVALID` | `Bearer error="invalid_token"` |
| `jti`、`kid`、Fence或 `client_id` 已吊销 | 401 | `ACCESS_TOKEN_INVALID` | `Bearer error="invalid_token"` |
| Service Token Scope不足 | 403 | `ACCESS_TOKEN_SCOPE_INSUFFICIENT` | `Bearer error="insufficient_scope", scope="..."` |
| Redis不可用或 Ready=false | 503 | `TOKEN_REVOCATION_STATUS_UNAVAILABLE` | 无 |
| 无健康实例 | 503 | `UPSTREAM_UNAVAILABLE` | 无 |
| 未知/矛盾 security | 构建失败 | 不适用 | 不适用 |

Token类型错误统一为 401，不泄露另一类 Token是否有效。required scope来自公开契约，可以在 403 challenge中按字典序表达。Gateway与 Starter必须使用相同 code和语义；合格的下游 Problem Details继续原样透传。

## 13. 现有服务迁移

移除：

- 生成器 owner → Target switch；
- `GatewayOpenApiRoutes.Target` 枚举；
- 质量门中的 Gateway Target switch和独立服务集合；
- 新服务必须修改 Gateway Java的扩展点。

IAM、Tenant Access、Entitlement全部从 Registry取得 serviceId并经同一通用 Route Function构建。JWKS访问 IAM的配置仍是 IAM安全权威依赖，不属于公开 owner路由枚举，但 IAM serviceId也必须从 Registry解析或校验。

迁移必须证明：

- 公开 method/path、operationId与 serviceId映射不变；
- Login、Refresh Cookie、Tenant Switch、Logout Optional、Platform/Tenant User Required行为不变；
- 404、405、Allow、Trace、Authorization转发和上游错误语义不变；
- 新增一个无正式 operation的 Nacos服务仍为 404；
- Audit注册存在但不获得公网路由。

## 14. 非生产真实接收端

在 `test-support` 增加最小 Spring Boot接收端：

- 不进入生产 Service Registry、Compose默认 profile或 Helm；
- 使用测试 Registry overlay和独立测试 OpenAPI；
- 使用真实 Starter、Redis Revocation与 serviceId发现；
- 暴露只为验收存在的 Service-required operation；
- 返回的测试结果只证明 `clientId/scopes` Context，不回显原 Token；
- 能证明新增 serviceId不修改 Gateway Java。

Compose验收使用真实 IAM、Redis、Nacos、Gateway和测试接收端。该证据必须标记为“平台机制验收”，不能标记为生产 Runtime业务闭环。

## 15. 测试矩阵

构建与契约：

1. 三个现有目标全部由 Registry驱动；
2. Catalog从根 OpenAPI确定性生成；
3. 未登记 serviceId、未知 Scheme、非法/冲突路径、重复 operationId构建失败；
4. Service Scope为空、未知、重复、内部 Scope用于公网时构建失败；
5. Catalog缺失、空、版本错误、字段非法时启动失败；
6. 测试 serviceId接入不修改 Gateway Java。

认证与转发：

1. User-required拒绝缺失、Service、非法或已吊销 User Token；
2. Service-required拒绝缺失、User、非法或已吊销 Service Token；
3. 全部 required scopes存在时放行，额外合法 Scope允许；
4. 缺任一 Scope返回稳定 403；
5. `client_id` 吊销对同一未过期 Token即时生效；
6. Redis不可用或 Ready=false返回 503且不转发；
7. Starter复验失败不因 Gateway已放行而绕过；
8. Service链路不建立 User Tenant Context；
9. Gateway删除、Starter直连拒绝保留 Header；
10. 无健康实例返回稳定 503。

回归：

1. 所有现有公开 method/path与 operationId不变；
2. IAM、Tenant Access、Entitlement不串路由；
3. Logout Optional无 Bearer、非法 Bearer和 Redis不可判定语义不变；
4. Tenant Switch只用 Cookie定位会话；
5. 404、405、Allow、Trace Context与 Forwarded Header策略不变；
6. Nacos只有注册、没有 operation时仍返回 404。

专项测试可使用 Stub证明局部分支，但最终验收必须复用真实 IAM、Redis、Nacos和真实 Starter接收端；Mock-only happy path或静态文件存在不能作为完成证据。

## 16. Nacos 与部署边界

- Route Catalog、认证分类、Scope、CORS与 TLS不进入 Nacos；
- Gateway和服务应用继续 `refreshEnabled=false`；
- Registry控制“能否成为目标”，部署 ACL控制“Gateway能否发现”，OpenAPI控制“哪些 operation公开”；三者缺一构建或部署验证失败；
- 新实例扩缩容、摘除和故障切换由 Nacos动态完成；
- 新 operation必须构建和滚动部署 Gateway及接收服务；
- 不承诺无构建、无部署新增公网路由。

## 17. 文件影响范围

预计涉及：

- `saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml`、OpenAPI README与兼容性基线；
- `saas-forge-contracts/services/**`、`saas-forge-contracts/security/**`、新的 Route Catalog契约模块；
- 根 POM依赖/插件版本管理；
- Gateway生成器、目录读取、路由、认证、Header与测试；
- `saas-forge-sdk/saas-forge-java/saas-forge-sdk-auth` 与 Spring Boot Starter；
- 各服务 Catalog依赖和必要接收配置；
- `saas-forge-quality-gates` Registry、契约与一致性门禁；
- `deploy/nacos/**`、Compose、Helm与 discovery ACL验证；
- `test-support` 非生产接收端；
- API、SDK、安全、部署和 MVP计划文档；
- ADR 0025的演进引用与 ADR 0034。

不得修改无关领域实现或未发布的 Runtime业务代码。

## 18. 实施顺序（to-spec）

1. 在根 POM登记并管理 OpenAPI Parser版本；建立 Service/Scope Registry及 Schema。
2. 建立可发布 Route Catalog契约模块和语义解析器；先写构建失败测试。
3. 生成 v1 JSON Catalog；建立确定性、冲突、版本与 Registry交叉门禁。
4. 更新正式 OpenAPI的 Cookie、Basic、User、Service security表达；保持 operation行为。
5. 将 Gateway Target枚举/switch替换为 `serviceId`，通用构建 Route Function。
6. 接入 Service Token组合验证、Scope AND与 `client_id` 即时吊销，保留 Ready失败关闭。
7. 实现共享 Catalog加载与最小 HTTP认证 Starter，修正撤销不可用异常分类。
8. 实现 Gateway删除、Starter直连拒绝保留 Header。
9. 迁移现有服务并运行行为回归测试。
10. 建立非生产接收端、测试 Registry/OpenAPI和 Compose真实验收。
11. 更新 Nacos/部署 ACL、一致性校验和相关文档。
12. 运行相关模块 `verify`、完整质量门和 Compose验收，记录证据边界。

## 19. 完成标准

只有以下全部成立，Issue才可关闭：

- 现有三个服务全部使用统一 serviceId机制；
- 新测试服务接入不修改 Gateway Java；
- Gateway与 Starter从同一不可变 Catalog执行业务无关认证；
- User/Service Token、Scope、吊销、Redis Ready和错误矩阵有正反测试；
- 真实 IAM、Redis、Nacos、Starter接收端 Compose验收通过；
- 现有公开路由和 User/Cookie行为无回归；
- 注册服务不会自动产生公网入口；
- 文档明确首个生产 Runtime operation仍待后续领域 Issue。

完成本规格只勾选 MVP总项的 Gateway子项；Audit子项另见 [三类成功事实的 Audit Record 消费闭环](24-audit-success-fact-consumption.md)。父项必须等待两个子项都有直接证据。
