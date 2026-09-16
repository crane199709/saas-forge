# OAuth 2.0 Client Credentials 管理规格

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

> 原始交付来源为 [GitHub Issue #64](https://github.com/crane199709/saas-forge/issues/64)；本文的写读边界已按当前实现更新（Client 列表、操作记录与凭据状态查询已作为增量交付，见 [OAuth Client 读取验收记录](acceptance/issue-178-oauth-client-reads.md)）。

本规格对应 [MVP 开发计划第 2 阶段](16-mvp-development-plan.md)中的 OAuth 2.0 Client Credentials 管理条目，建立在已完成的最小 Client Credentials Token 签发、Service Access Token 校验和保留服务 Client bootstrap 之上。领域语言以 [IAM Context](../saas-forge-services/iam-service/CONTEXT.md) 为准，架构取舍见 [ADR 0033](adr/0033-oauth-client-management-uses-immediate-revocation-and-replacement-recovery.md)。

## 1. 交付边界

本切片交付：

- Platform Admin 创建 `RUNTIME_SERVICE` OAuth Client，并只展示一次初始 Secret；
- 读取单个 Client 的非敏感详情；
- 为 Runtime 或 Reserved Client 执行固定 24 小时重叠轮换；
- 在初始 Secret 或轮换 Secret 的响应遗失后执行一次受限的 Secret Issuance Recovery；
- 不可逆吊销整个 Client，立即阻止新 Token 签发和全部已签发 Service Access Token；
- 正式轮换后的保留 Client bootstrap 只读校验，以及已吊销保留 Client 的部署侧 Replacement Job；
- Client 生命周期 Committed Fact Event、Redis Registry、迁移、契约和自动化验收。

本切片不交付 Client 列表、名称/类型/Scope 修改、Client 恢复、单独吊销某把 Secret、用户授权码流程、外部 OAuth/OIDC、API Key、动态 Scope 注册、控制台页面或 Audit Record 消费。Gateway 按 Scope 的业务路由策略仍属于开发计划下一条；本切片只保证所有实际 Service Token 接收端具备一致的密码学与即时吊销校验能力。

## 2. 不变量

1. OAuth Client 只代表 `client_id` 与显式 Scope，不代表 Identity、Membership、Tenant、Role 或 Permission。
2. Service Access Token 的 Claim 白名单保持 `iss`、`aud`、`iat`、`exp`、`jti`、`sub`、`client_id`、`scope`，不得增加用户或 Tenant Claim，也不得建立用户 Tenant Context。
3. Secret 是 CSPRNG 生成的 32 字节、43 字符无填充 Base64URL；IAM 只保存 SHA-256 摘要，日志、事件、Trace、幂等记录和 Problem Details 均不得包含 Secret 或摘要。
4. `RUNTIME_SERVICE` 只能从 `runtime:read`、`runtime:quota:write` 中选择至少一项；`RESERVED_SERVICE` 的 Scope 由服务键精确决定，不能由 HTTP 请求或 Replacement Job 输入。
5. Client 名称、类型、保留服务键和 Scope 创建后不可变。Client 状态仅为 `ACTIVE`、`REVOKED`，吊销不可逆，首次 `revokedAt` 与操作者不可覆盖。
6. 常规轮换的新 Secret 立即有效，旧稳定 Secret 固定在首次轮换提交后 24 小时失效；重叠期间拒绝新的常规轮换。安全事件不缩短窗口，而是吊销整个 Client。
7. Secret Issuance Recovery 只替代可能未送达的新 Secret，不恢复明文、不延长旧稳定 Secret 的截止时间，并且每个原始成功签发最多执行一次。
8. Revocation Index 未就绪、Redis 不可用或安全状态无法确定时，Service Token 签发和使用均失败关闭。

## 3. Client 类型与固定 Scope

| Client 类型/服务键 | 创建责任 | 精确 Scope |
|---|---|---|
| `RUNTIME_SERVICE` | Platform Admin REST API | `runtime:read`、`runtime:quota:write` 的非空子集 |
| `RESERVED_SERVICE / IAM` | bootstrap 或 Replacement Job | `tenant-access:membership:read` |
| `RESERVED_SERVICE / TENANT_ACCESS` | bootstrap 或 Replacement Job | `iam:identity:write`、`iam:password-setup:write`、`iam:platform-role:read`、`iam:sessions:write`、`entitlement:quota:write` |
| `RESERVED_SERVICE / ENTITLEMENT` | bootstrap 或 Replacement Job | `tenant-access:tenant:read`、`iam:platform-role:read` |

冻结的 v1 `RuntimeScope` 枚举以兼容性新增方式扩展为当前九种已识别 Scope，使保留 Client 的轮换和详情响应能如实表示 Scope。公开创建接口仍只允许两个 Runtime Scope；请求七种内部 Scope 时返回 `403 / OAUTH_CLIENT_SCOPE_GRANT_FORBIDDEN`。`RuntimeScope` 的命名只在未来新 API 主版本中修正。

## 4. 管理认证与请求来源

- 所有管理接口只接受经 Gateway 到达的 Platform User Access Token；Token 必须没有 `membershipId`、`tenantId`，并表示 Platform Context。
- Gateway 按现有 User Token 规则完成验签、`jti`/`kid`、Index Ready 与浏览器来源/CSRF 检查；IAM 仍独立校验原始 Bearer Token、即时撤销状态和当前 `PLATFORM_ADMIN` 授予。
- IAM 不信任客户端或 Gateway 提供的 `identityId`、`membershipId`、`tenantId` 请求头。操作者 Identity 只从已验证 Platform Token 得出。
- Tenant Context User Token、Service Access Token、Tenant Admin 和普通用户分别以 `403` 或 `401` 拒绝，不得因同一 Identity 另有 Platform Role 而接受 Tenant Token。
- 所有 Secret 成功响应使用 `Cache-Control: no-store`，不得被 Gateway、代理或客户端缓存；既有日志白名单继续禁止请求/响应体和 Authorization。

## 5. REST 契约

### 5.1 路径

| 方法与路径 | 输入 | 成功结果 |
|---|---|---|
| `POST /api/v1/platform/oauth-clients` | `Idempotency-Key`、现有 `CreateOAuthClientRequest` | `201 OAuthClientSecretResult`；`Location` 指向详情资源 |
| `GET /api/v1/platform/oauth-clients/{clientId}` | `clientId` | `200 OAuthClientDetail`，不含任何 Secret 信息 |
| `POST /api/v1/platform/oauth-clients/{clientId}/secret-rotations` | `Idempotency-Key` | `200 OAuthClientSecretResult` |
| `POST /api/v1/platform/oauth-clients/{clientId}/secret-issuance-recoveries` | 新 `Idempotency-Key`、`SecretIssuanceRecoveryRequest` | `200 OAuthClientSecretResult` |
| `POST /api/v1/platform/oauth-clients/{clientId}/revocations` | `Idempotency-Key` | `204` |

`SecretIssuanceRecoveryRequest` 只有必填的 `originalIdempotencyKey`。它必须定位同一操作者对同一 Client 已成功完成的创建或轮换；恢复请求使用独立的新幂等键。创建响应遗失时，调用方先以原键重试创建，从 `CLIENT_SECRET_ALREADY_REVEALED` Problem 扩展字段取得 `clientId`，再创建恢复资源。

`OAuthClientDetail` 必含 `clientId`、`displayName`、`clientType`、`allowedScopes`、`status`、`createdAt`、`updatedAt`，并包含可空的 `reservedServiceKey`、`revokedAt`。它不包含 Secret、Secret ID、摘要、Secret 数量或可推断摘要的数据。

### 5.2 稳定失败

| HTTP / code | 适用场景 |
|---|---|
| `400 / IDEMPOTENCY_KEY_REQUIRED` | 变更操作缺少幂等键 |
| `400 / IDEMPOTENCY_KEY_INVALID` | 幂等键不是规范 UUIDv7 |
| `400 / CLIENT_SECRET_RECOVERY_REQUEST_INVALID` | 原始键格式非法或字段不完整 |
| `401 / ACCESS_TOKEN_INVALID` | User Token 缺失、无效或已撤销 |
| `403 / PLATFORM_CONTEXT_REQUIRED` | 使用 Tenant Context User Token |
| `403 / PLATFORM_ADMIN_REQUIRED` | 当前 Identity 无有效 Platform Admin 授予 |
| `403 / OAUTH_CLIENT_SCOPE_GRANT_FORBIDDEN` | Runtime 创建请求包含内部 Scope |
| `404 / OAUTH_CLIENT_NOT_FOUND` | 目标 Client 不存在 |
| `409 / IDEMPOTENCY_REQUEST_IN_PROGRESS` | 同一操作仍在提交，附 `Retry-After` |
| `409 / IDEMPOTENCY_KEY_REUSED` | 同一操作者与键绑定了不同请求指纹 |
| `409 / CLIENT_SECRET_ALREADY_REVEALED` | 成功签发 Secret 的操作被重放；Problem 可附非敏感 `clientId` |
| `409 / CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE` | 24 小时重叠尚未结束 |
| `409 / CLIENT_SECRET_RECOVERY_NOT_ALLOWED` | 操作者/Client/原操作不匹配、超过十分钟、已恢复或恢复响应再次遗失 |
| `409 / OAUTH_CLIENT_REVOKED` | 对已吊销 Client 执行详情以外的非吊销变更 |
| `503 / TOKEN_REVOCATION_STATUS_UNAVAILABLE` | Redis/Ready 状态无法安全判定 |

重复吊销是终局幂等成功：首次请求写 Redis、PostgreSQL 与唯一事件并返回 `204`；之后相同或不同操作者、相同或不同键均返回 `204`，不改变首次 `revokedAt`/操作者、不重复发事件。不存在的 Client 仍返回 `404`。

### 5.3 Secret 签发幂等例外

Client 创建、Secret 轮换和 Secret Issuance Recovery 不能遵循通用的“24 小时内原样重放首次响应”，因为响应含有只允许展示一次的 Secret。它们采用以下窄例外：

- 以 `(actorIdentityId, idempotencyKey)` 在全部 OAuth Client 变更操作中唯一标识请求；
- 永久保留操作类型、Client ID、规范化请求指纹、结果状态、原操作关联和完成时间；
- 不保存 Secret、摘要或完整响应；
- 首次成功时只返回一次 Secret，之后重放返回 `CLIENT_SECRET_ALREADY_REVEALED`；
- 格式/字段校验 `400` 和未提交业务结果的基础设施 `5xx` 不占用键；
- 稳定业务 `4xx` 可以保存非敏感结果，但不能把原始请求中的 Secret 或 Authorization 纳入指纹；
- 并发请求通过非阻塞、事务级操作锁返回统一的 `IDEMPOTENCY_REQUEST_IN_PROGRESS`，不得形成永久卡住的 `IN_PROGRESS` 记录。

不含 Secret 的吊销继续遵守通用幂等结果重放规则。

## 6. 应用服务与事务边界

新增一个 OAuth Client Management 应用层边界，Controller 只负责契约映射，不直接生成 Secret、调用仓储或使用 `Instant.now()`。该边界组合：

- Platform Token 与 Platform Role Authorizer；
- CSPRNG Secret Issuer 与摘要器；
- OAuth Client/Secret 仓储；
- 永久幂等操作仓储和事务级操作锁；
- Client Revocation Coordinator；
- IAM Transactional Outbox；
- 注入的 `Clock` 与 UUIDv7 生成器。

创建、轮换、恢复分别在一个数据库事务中完成操作锁、幂等检查、Client/Secret 变更、非敏感终态记录和 Outbox append；Spring 事务成功提交后 Controller 才能获得并返回内存中的明文 Secret。事务回滚时不得返回 Secret，也不得留下完成记录或事件。

吊销采用安全顺序：先幂等写入 Redis `client_id` 拒绝 Key，再在一个 PostgreSQL 事务中锁定 Client、固定首次吊销、吊销全部 Secret、记录幂等结果并 append 事件。Redis 成功但数据库提交失败时保留额外拒绝；同键重试重复 Redis 写并继续数据库提交。签发方遇到“数据库 ACTIVE、Redis 已拒绝”的不一致状态时返回 `TOKEN_REVOCATION_STATUS_UNAVAILABLE`，不能签发一枚所有接收方都会拒绝的 Token。

## 7. PostgreSQL 与 Flyway

不得修改已发布迁移。使用下一可用 Flyway 前向版本扩展 `iam_oauth_clients`：

- `client_type TEXT`：`RUNTIME_SERVICE` 或 `RESERVED_SERVICE`；
- `reserved_service_key TEXT`：Reserved 时为 `IAM`、`TENANT_ACCESS`、`ENTITLEMENT`，Runtime 时为空；
- `updated_at TIMESTAMPTZ`：创建时等于 `created_at`，轮换、恢复或首次吊销时更新。

迁移按三个保留服务的精确 Scope 集合回填 Reserved 类型，仅含两个 Runtime Scope 的记录回填 Runtime 类型；任何其他历史组合使迁移失败，不根据 `display_name` 猜测。回填后增加：

- 类型枚举约束；
- 类型与 `reserved_service_key` 的互斥一致性约束；
- Runtime Scope 非空子集约束；
- Reserved 服务键与精确 Scope 集合约束；
- 每个 `reserved_service_key` 最多一个 ACTIVE Client 的部分唯一索引；
- `client_type`、`updated_at` 非空约束。

新增 `iam_oauth_client_management_operations` 保存永久非敏感幂等终态，至少包含 UUIDv7 `id`、操作者 Identity、幂等键、操作类型、Client ID、SHA-256 请求指纹、可选原操作 ID、可选内部 Secret 记录 ID、稳定 HTTP 结果和完成时间。唯一约束覆盖 `(actor_identity_id, idempotency_key)`；每个成功创建/轮换操作最多关联一个成功恢复操作。表中不得保存 Secret、Authorization、完整响应或可用于离线验证 Secret 的材料。

新增 `iam_reserved_service_client_replacements` 保存 `replacementRequestId`、服务键、旧/新 Client ID、非敏感请求指纹和完成时间。完全相同重放返回 `ALREADY_REPLACED`；相同请求 ID 绑定不同输入时失败。旧 Client 永久保持 REVOKED，新 Client 使用新 UUIDv7 ID。

运行 SQL 继续只存在于 MyBatis Mapper XML；Mapper Java 接口与 XML 语句一一对应。`iam_app` 只获得所需的 `SELECT`、`INSERT`、`UPDATE`，不得获得 DDL 或物理删除权限。

## 8. Redis Revocation Index

在 `saas-forge-contracts/redis/registry/iam-service.json` 登记：

```text
sf:<environment>:iam-service:oauth-client-revocation:v1:<client_uuid>
```

- 权威来源：PostgreSQL `REVOKED` OAuth Client；
- 唯一写入者：`iam-service`；
- 读取者：IAM Token 签发、Gateway、Tenant Access、Entitlement、其他 Starter 接入服务；
- 值：布尔拒绝标记；
- TTL：无，Client 吊销不可逆；
- 标识符：规范 UUIDv7 Client ID，不含 Secret；
- 最大基数：环境内累计已吊销 Client 数量；
- 故障策略：fail-closed；
- 重建：Ready=false 时以 PostgreSQL 全量 REVOKED Client 重建该命名空间，完成后 Ready=true；重建可在未就绪窗口安全移除数据库中不存在的额外拒绝。

现有 Ready 和 Signing `kid` Registry 的读取者同步扩展到全部 Service Token 接收端。Client `jti` 不单独持久化或登记；紧急撤销粒度是整个 Client。

## 9. Service Access Token 授权

SDK 将当前校验职责拆为：

```text
ServiceAccessTokenSignatureVerifier
  └─ RS256、typ、kid、Issuer、Audience、时间、Claim 白名单、client_id、Scope

ServiceAccessTokenAuthorizer
  ├─ SignatureVerifier
  ├─ Revocation Index Ready
  ├─ Signing kid revocation
  └─ OAuth client_id revocation
```

Gateway 和所有 gRPC/HTTP 服务接收端只允许依赖 Authorizer。底层 Signature Verifier 保持纯密码学与声明校验，供隔离单测使用；质量门禁扫描生产接收端，拒绝直接依赖底层 Verifier。Client Credentials Token 签发在摘要认证成功后仍检查 Client ACTIVE、Ready 与 `client_id` 拒绝状态。

## 10. 保留 Client 部署生命周期

### 10.1 Bootstrap 重跑

- 尚无对应 Client：按现有完整三服务集合执行首次原子创建；
- 已存在 ACTIVE Reserved Client：Client ID、服务键和固定 Scope 必须一致，挂载 Secret 摘要匹配任意当前有效 Secret 即返回 `ALREADY_INITIALIZED`，不修改数据库；
- 挂载 Secret 已过期或已吊销：失败并要求更新外部 Secret；
- Client 已 REVOKED：失败并要求 Replacement Job，bootstrap 不得复活。

### 10.2 Replacement Job

IAM 同一制品提供独立非 Web Job，输入固定 UUIDv7 `replacementRequestId`、服务键、旧 Client ID、新 UUIDv7 Client ID 与新 Secret 文件。Job 只在旧 Client 已吊销、服务键匹配、新 ID 未使用且该服务不存在其他 ACTIVE Client 时创建替代；Scope 和名称从服务键推导，不接受输入。

完全相同的请求重放返回 `ALREADY_REPLACED`；相同请求 ID 下服务键、Client ID 或 Secret 摘要不同均失败并转人工处理。Job 不返回或记录 Secret，操作者在事件中表示为 `DEPLOYMENT` 与 `deploymentOperationId`。

## 11. Committed Fact Event

新增并登记以下 CloudEvents JSON Schema，Topic 均为 `saas.forge.<environment>.iam-service.events`，Ordering Key 为 `clientId`：

| type | 事实 |
|---|---|
| `com.saas.forge.iam.oauth-client.created.v1` | Runtime 创建或 Reserved Replacement 已提交 |
| `com.saas.forge.iam.client-secret.rotated.v1` | 常规 Secret 轮换已提交 |
| `com.saas.forge.iam.oauth-client.revoked.v1` | Client 首次不可逆吊销已提交 |
| `com.saas.forge.iam.client-secret.issuance-recovered.v1` | 未送达 Secret 已被替代 |

公共数据白名单包含 `clientId`、`operationId`、动作对应结果、`occurredAt`，恢复事件增加 `originalOperationId`。操作者使用互斥结构：

- `actorType=IDENTITY` 时必须且只能有 `actorIdentityId`；
- `actorType=DEPLOYMENT` 时必须且只能有 `deploymentOperationId`。

不得出现 Secret、Secret ID、摘要、Token、Authorization、工作负载凭据、邮箱或物理 Redis Key。重复吊销不产生第二个事件；Outbox 重投保留同一 CloudEvent ID。

## 12. 实现接缝

当前已有的 `OAuthClient`、`ClientSecret`、`OAuthClientRepository`、`MyBatisOAuthClientRepository`、`OAuthClientMapper.xml`、`ClientCredentialsTokenService`、`ServiceAccessTokenVerifier`、`OAuthClientsController` 和 Reserved bootstrap 是修改起点，不另建平行实现。实现应：

- 将 Controller 中的随机数、时间和生命周期编排下沉到应用服务；
- 保留仓储接口的稳定公开方法，只有规格需要时才增加兼容方法；
- 在现有 IAM Revocation Index 协调器、Ready 重建和 Key Factory 上扩展 Client 维度；
- 复用现有 Transactional Outbox，不创建共享 Outbox 或同步 Kafka 发布；
- 不引入 Spring Authorization Server、新密码库、分布式事务或新第三方依赖。

## 13. 验收与测试

### 13.1 领域与应用单测

- Runtime/Reserved 类型和精确 Scope 矩阵；
- 名称、类型、服务键和 Scope 不可变；
- Secret 32 字节格式、摘要-only、固定 24 小时重叠和重叠中拒绝再轮换；
- 十分钟边界前后、原操作者/其他操作者、同 Client/其他 Client、首次/第二次恢复；
- 恢复吊销上一把新 Secret 且不延长旧稳定 Secret；
- 首次吊销固定时间和操作者，任意重复吊销保持 `204` 且只发一个事件。

### 13.2 PostgreSQL/Testcontainers

- 新列回填、无法分类数据 fail-closed、全部 CHECK/唯一约束和最小权限；
- 创建、轮换、恢复、吊销、幂等终态与 Outbox 的事务原子性；
- 同键并发只执行一次，其他请求得到 `IDEMPOTENCY_REQUEST_IN_PROGRESS`；
- 同键不同指纹、永久重放、恢复唯一关联；
- 数据库中不存在明文/可解密 Secret，历史摘要按生命周期保留；
- Repository Standards 继续强制 SQL 只在 Mapper XML。

### 13.3 HTTP、Gateway 与安全集成

- Platform Token 允许；Tenant Token、Service Token、普通用户、失效角色和伪造 Identity 头拒绝；
- 创建响应只展示一次并带可读取 `Location`，所有 Secret 响应 `no-store`；
- Runtime 创建拒绝七种内部 Scope；Reserved 轮换/详情可返回九种 Scope；
- 普通重放、处理中、指纹冲突、恢复窗口/次数和完整错误码矩阵；
- 旧、新 Secret 在重叠内均可签发，截止点后仅新 Secret 可用；
- Redis 不可用/Ready=false 时签发和验证均失败关闭；
- 吊销前签发且尚未过期的 Service Token 在 Client 吊销提交后被 IAM、Tenant Access、Entitlement 和 Gateway/Starter 接收端立即拒绝；
- Service Token Claim 永远没有 Identity、Membership、Tenant、Role、Permission，接收后不会建立 Tenant Context。

### 13.4 部署与事件

- bootstrap 首次创建、轮换后新/旧有效 Secret 校验、过期 Secret 失败和 REVOKED 不复活；
- Replacement Job 成功、完全重放、输入冲突、并发替换和每服务单 ACTIVE 约束；
- 四种事件 Schema、Registry、白名单、Ordering Key、Outbox 原子性和重复发布；
- 日志、Problem、事件、Trace 和测试输出均不含 Secret、摘要、Authorization 或 Token。

### 13.5 验证命令

实现后至少执行相关 IAM、SDK、Gateway 和跨服务定向测试，并以：

```bash
./mvnw --batch-mode --no-transfer-progress -pl quality-gates -am verify
```

作为完整门禁。涉及 Compose 的最高集成验收必须使用真实 PostgreSQL、Redis、IAM Token 端点和至少一个真实服务接收端，不得以 Mock-only happy path 证明即时吊销闭环。

## 14. 完成判定

只有在上述契约、迁移、Redis Registry、事件、实现和自动门禁全部通过，并取得“旧 Service Token 在整 Client 吊销后立即被真实接收端拒绝”以及“Service Token 不产生 Tenant Context”的直接证据后，才能勾选 [开发计划](16-mvp-development-plan.md)中的对应条目。仅有领域类、Controller 单测、静态契约或 Token 自然过期不能视为完成。
