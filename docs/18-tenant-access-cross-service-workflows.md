# Tenant Access 跨服务工作流契约

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。

本文冻结 Tenant 创建与管理员初始化、Invitation 激活、Password Recovery、Tenant 切换、成员禁用、Tenant 冻结与关闭的跨服务责任。它补充而不替代[核心领域契约](17-core-domain-contracts.md)：未在本文列出的状态、错误码和恢复动作不得由实现自行推定。

## 数据所有权与通用规则

| 组件 | 权威数据与责任 |
|---|---|
| Tenant Access | Tenant、Membership、Tenant Role、Invitation，以及其根工作流、补偿/重试工作项与 Outbox。|
| IAM | Identity、密码凭据、会话、Access Token `jti` 黑名单和会话撤销请求去重。|
| Entitlement | Subscription、权益快照、Quota Usage 与带 `operationId` 的 Quota Operation。|
| Audit | 只追加的 Audit Record；它消费事实事件，不裁决流程成功。|
| Gateway | 公网路由与安全边界；不持有领域数据、不编排领域步骤。|

同步内部调用除生产传输层 mTLS 外还必须使用 Client Credentials 服务 Token，并由接收方校验 `client_id` 与精确 Scope。IAM 调 Tenant Access 查询或验证 Membership 只使用 `tenant-access:membership:read`；Entitlement 调 Tenant Access 校验首个 Subscription 的 Tenant 资格只使用 `tenant-access:tenant:read`；Tenant Access 调 IAM 确认 Identity、投递 Password Setup 与复核 Platform Role 分别使用 `iam:identity:write`、`iam:password-setup:write`、`iam:platform-role:read`，调 Entitlement 计量只使用 `entitlement:quota:write`。Tenant Operation Target 只表达操作目标，不替代服务身份或 Scope。Tenant Access 与 Entitlement 的平台管理 API 必须向 IAM 复核调用 `identityId` 当前持有要求的 Platform Role，不能根据 Token 缺少 Tenant Claim 推定；IAM 或 Tenant Access 权威校验不可用时 fail-closed。

本流程的内部同步契约固定为版本化 Protobuf：IAM `IdentityProvisioningService.EnsureIdentity(requestId, email, displayName)` 返回 `identityId` 与 `PASSWORD_READY`、`SETUP_ALLOWED` 或 `RECOVERY_REQUIRED`；IAM `PasswordSetupService.DeliverPasswordSetup(requestId, identityId)` 只按 IAM 权威 Identity 读取收件地址，调用方不得传邮箱；IAM `PlatformAuthorizationService.CheckPlatformRole(identityId, roleKey)` 只返回当前是否授权，不暴露全部角色；Tenant Access `TenantProvisioningQueryService.CheckInitialSubscriptionEligibility(tenantId)` 只返回 `PENDING_ELIGIBLE`、`NOT_FOUND`、`INVALID_STATE` 或 `EXPIRY_REACHED`；Entitlement `QuotaCommandService.Consume/Release(tenantId, quotaCode, amount, operationId, purpose)` 返回当前 `usage`、`limit` 与是否为幂等重放。Entitlement 创建首个 Subscription 前必须调用 Tenant Access 资格校验，只允许尚未到绝对 `expiresAt` 的 `PENDING` Tenant；不得缓存或复制 Tenant 状态，也不得用内部 REST 或共享领域实体替代这些边界。

管理员初始化的 Quota Command 固定 `purpose = TENANT_ADMIN_INITIALIZATION`。只有 `tenant-access-service`、`entitlement:quota:write`、`max_users` 与 `amount = 1` 的组合可在 `PENDING` Tenant 上执行 provisioning `consume`；对应补偿 `release` 使用相同 purpose。purpose 必须进入 Quota Operation 幂等指纹和事件，普通 Runtime Quota 调用不能使用该例外。Entitlement 不回调 Tenant Access 校验该命令，避免形成同步调用环。

每个公网状态变更由接收服务以 `(调用方, Idempotency-Key)` 绑定唯一根工作流和请求指纹。根工作流一次性持久化下游子操作 ID；超时、进程重启和调用重试必须恢复它，不能创建新 ID。下游服务按自己的稳定子操作 ID 去重：IAM 使用调用服务与请求 ID，Entitlement 使用既有 `operationId`。根服务在同一事务写入本地领域事实、稳定 HTTP 结果、Outbox 和尚未完成的补偿/重试工作项。外部 Key 不跨服务透传。

根工作流采用同步快路径与持久化后台恢复。HTTP 请求在首次跨服务调用前必须持久化根状态、稳定子操作 ID 与下一步动作；请求超时、服务重启或依赖暂时不可用后，Tenant Access Worker 通过数据库租约按业务串行化键继续同一流程，客户端同 Key 重试只查询、协助推进或重放结果，不是恢复发生的前提。Quota 补偿与已提交激活后的 Password Setup 投递即使客户端不再请求也必须持续执行。重试退避由环境配置，达到某个重试次数不得伪造成功或生成新的子操作 ID。

所有事件经 Transactional Outbox 发布，只说明来源服务已提交的事实；它们不是命令，也不构成任何同步步骤的成功条件。消费者以 CloudEvents `id` 去重。事件、日志与 Trace 只使用允许的 ID、状态、时间、操作者和 `traceId`，不得包含密码、激活令牌、Access/Refresh Token、Client Secret 或邮箱原文。

## Tenant 创建与管理员初始化

Tenant Access 接收平台创建 Tenant 的请求并仅在自己的事务中创建 `PENDING` Tenant、HTTP 幂等结果和 `com.saas.forge.tenant.created.v1` Outbox 事件；创建 Tenant 不调用 IAM 或 Entitlement。Subscription 由 Entitlement 的独立平台操作创建，管理员初始化前必须已有有效 Subscription 和足够的 `max_users` 额度。

Tenant `expiresAt` 是创建时确定的绝对平台访问截止时间，不从激活时重新计时，也不替代 Subscription `endsAt`。创建时非空值必须晚于服务端当前时间；管理员初始化必须在调用 IAM 或扣减 Quota 前复核，已经达到时返回 `409 / TENANT_EXPIRY_REACHED`，Tenant 保持 `PENDING` 且不产生跨服务副作用。

当前最小生命周期切片只实现创建 `PENDING` 与管理员初始化完成后的 `PENDING → ACTIVE`。虽然 v1 OpenAPI 已声明 Tenant 冻结与恢复资源，但它们必须与 IAM 会话撤销及 `jti` 黑名单链路同步交付；在该安全前置完成前不得只提交 `SUSPENDED` 或 `ACTIVE` 状态变更。`CLOSED` 也不属于本切片的公开操作。

Tenant Access 平台操作在验证 User Access Token 并经 IAM 复核 Platform Role 后，才能把服务器生成或路径定位的 Tenant ID 作为 Tenant Operation Target，并以事务级 `app.tenant_id` 执行 RLS 受限事务。后台 Worker 只从权威工作流记录恢复该目标；任何请求输入都不能直接设置数据库上下文，运行账号不得拥有 `BYPASSRLS` 或迁移权限。IAM 的 Accessible Membership 查询继续使用按 Identity 限定、字段与数量受限的 `SECURITY DEFINER` 函数，并在进入该查询前验证 `tenant-access:membership:read`。

管理员初始化的根服务为 Tenant Access，且按 `tenantId` 串行化。它持久化 `identityRequestId`、`consumeOperationId` 和 `releaseOperationId` 后按如下顺序执行：

1. 调 IAM 确认规范化邮箱对应的 Identity；已有有效 Password Credential 时复用，完全没有 Credential 记录时允许后续 Password Setup。存在有效或过期 Initial Platform Credential，或存在已失效 Password Credential 时，在 Quota 扣减前返回 `409 / IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。新建 Identity 没有 Membership、没有可用密码凭据时不可登录，后续重试可复用它。
2. 调 Entitlement `consume(max_users, consumeOperationId)`；无有效 Subscription 或额度不足时不创建 Membership，Tenant 保持 `PENDING`。
3. 在 Tenant Access 单一事务内创建启用的初始管理员 Membership、写入每个 Tenant 唯一且不可变的 Initial Tenant Administrator 关系、幂等创建或确保固定 `roleKey = TENANT_ADMINISTRATOR` 且 `systemManaged = true` 的 Tenant Administrator Role、建立唯一 Membership–Role Assignment、将 Tenant 转为 `ACTIVE`，同时写入稳定 HTTP 结果和 `com.saas.forge.tenant.administrator-initialized.v1`。当前切片不创建 Permission 或 Role–Permission 数据，也不增加管理员标记字段。已 `ACTIVE` 的 Tenant 使用新幂等键再次初始化时返回 `409 / TENANT_ALREADY_INITIALIZED`，不得替换初始管理员关系。

第 2 步已成功而第 3 步未提交时，Tenant Access 必须进入 `COMPENSATING` 并以同一 `releaseOperationId` 补偿；补偿未完成时，同一外部 Key 返回 `503 / TENANT_ADMIN_INITIALIZATION_COMPENSATING` 与 `Retry-After`。补偿成功后，原根工作流稳定结束为 `409 / TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED`；客户端必须使用新的 `Idempotency-Key` 发起新根工作流，且新的 Identity、consume、release 子操作 ID 不得复用已被补偿的 ID。Identity 及其既有凭据永不被 Tenant 工作流删除或重置，Tenant 保持 `PENDING`。若该 Identity 尚无凭据，Tenant Access 在第 3 步提交时写入凭据注册工作项；其后以同一 `DeliverPasswordSetup requestId` 调用 IAM 创建和发送一次性、限时的密码设置链接。IAM 只在 SMTP 明确接受后记录稳定成功；未完成重试必须作废旧 Challenge 并生成新链接，迟到的旧邮件因此只含无效 Token。Tenant Access 持续退避重试该工作项，不回滚已激活的 Tenant，也不触发 Quota 补偿；IAM 不得持久化可恢复的明文或加密 Token。

初始管理员从未拥有任何 Credential 且密码设置链接已失效或未送达时，由 Tenant Access 拥有 Platform Admin 发起的 `POST /api/v1/platform/tenants/{tenantId}/administrator-password-setups` 受限重发流程。Tenant Access 必须通过不可变 Initial Tenant Administrator 关系定位目标 Membership 和 Identity，再请求 IAM 作废旧 Password Setup Challenge 并投递新链接；若 Identity 已有有效 Password Credential，则幂等完成且不得重置密码，若存在其他历史 Credential 则返回 `IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。该流程必须携带 `Idempotency-Key`，不向 Platform Admin 返回设置令牌、邮箱或投递内容，也不代替普通密码重置。

该重发 API 只有在 SMTP 明确接受最新有效 Challenge 邮件，或 IAM 确认已有有效 Password Credential 时返回 `204`。投递未完成时，Tenant Access 保留根工作流并返回 `503 / PASSWORD_SETUP_DELIVERY_PENDING` 与 `Retry-After`，后台 Worker 持续使用同一 `DeliverPasswordSetup requestId`；成功后相同外部 Key 稳定重放 `204`。当前切片不为此创建 Job 资源或返回 `202`；凭据处于恢复冲突状态时稳定返回 `409 / IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。

Password Setup Challenge 由 IAM 的 `POST /api/v1/auth/password-setups` 匿名兑换；请求只携带 43 字符无填充 Base64URL Challenge Token 与新密码，不接受 Identity、Membership 或 Tenant 标识，并受受控 Origin 与 `X-SF-CSRF` 防护。邮件链接仅使用 `https://console.<root>/password-setup#token=<token>`，不得将 Token 放入查询参数；控制台读取后立即清除 Fragment 并只在内存保存。成功兑换只建立该 Identity 的首个 Password Credential，不建立登录会话，用户随后必须通过正常登录取得上下文。

## Platform Admin 加入

IAM 接收现有 Platform Admin 按邮箱授予 `PLATFORM_ADMIN` 的操作，确认或创建目标 Identity；只为从未拥有任何 Credential 的 Identity 投递首次 Password Setup，不重置已有凭据。该流程不建立 Tenant Membership、不计入 Tenant `max_users`，也不自动登录或切换工作上下文。目标的历史 Credential 恢复继续遵循 IAM 既有安全边界，不得用 Setup 绕过。

角色授予与投递工作项的幂等、恢复及正式 API 须随切片冻结；不得把投递成功视为目标已建立密码。此处是加入范围决策，不表示管理 API 已实现。

撤销 `PLATFORM_ADMIN` 只影响平台授权范围：结束当前平台工作上下文会话、撤销该 Identity 仍有效的平台 `jti`，包括已切换到 Tenant 的会话历史上签发的平台 Token；当前合法 Tenant 会话保留。IAM 须补齐平台范围的持久撤权及恢复契约，不能用 Identity 全局撤销代替；已完成的撤销事实不会因后续重新授予而复活。

## Password Recovery

IAM 拥有 Identity 全局 Password Recovery 根工作流。有效恢复凭据验证并进入密码替换阶段后，须阻断该 Identity 在所有 Tenant、Platform、未选择工作上下文及其他浏览器中的访问与并发会话/Token 签发；故障时以持久工作流继续完成，不回滚旧密码或复活旧会话。只有密码替换及全部会话、有效 `jti` 撤销完成后才可返回成功。

当前 Membership/Tenant 目标撤销不能替代 Identity 全局屏障，也不能靠遍历 Membership 覆盖 Platform 或等待会话。Identity 级目标、签发屏障与恢复状态契约仍须本阶段补齐；恢复凭据策略见[核心领域契约](17-core-domain-contracts.md#password-recovery)。此处是已确认的安全完成边界，不表示现有撤销实现已支持 Identity 目标。

## Invitation 激活

Invitation 激活的公网资源属于 Tenant Access：`POST /api/v1/tenant/invitation-activations`。它不信任客户端提供的 Tenant 上下文，而由 Invitation 令牌定位 Invitation 和 Tenant；Gateway 只路由该请求。Tenant Access 是根服务并拥有 Invitation 的锁定、验证和 `PENDING → ACCEPTED` 状态迁移。新 Identity 须先从有效邀请进入独立 Password Setup，完成后登录，再显式接受邀请；最终接受要求与邀请邮箱匹配的当前登录身份，顺序修订见 [ADR 0059](adr/0059-invitation-acceptance-follows-password-setup-and-login.md)。

一次最终接受尝试固定 `consumeOperationId` 和 `releaseOperationId`，按以下顺序执行：验证令牌、Tenant 可访问性和 `PENDING` Invitation → IAM 权威确认当前密码凭据与匹配的登录 Identity → Entitlement `consume(max_users, consumeOperationId)` → Tenant Access 本地事务创建启用 Membership、接受 Invitation、写入 HTTP 幂等结果和 `com.saas.forge.invitation.accepted.v1`。Invitation 不重置既有密码，不预设角色，也不自动切换 Tenant Context。Password Setup 投递成功或 `EnsureIdentity` 首次幂等结果均不能替代当前凭据状态；等待会话身份证明的正式契约仍待冻结，Cookie 等浏览器管理边界不得作为业务参数。

新浏览器闭环只在受控启用 v2 的环境交付；IAM 的受限会话验证须通过正式认证契约支持没有业务 Access Token 的等待会话，业务资源继续按 v1 增量交付。实现与验证不隐式切换认证开关，见 [ADR 0052](adr/0052-unified-console-authentication-uses-versioned-session-protocol.md)。

扣减成功后的任何失败都只补偿 Quota：Tenant Access 以同一 `releaseOperationId` 重试，Invitation 保持 `PENDING`；Identity 和凭据不回滚。补偿未完成时拒绝新激活尝试并按既有 `503 / INVITATION_ACTIVATION_COMPENSATING` 契约返回。补偿完成后，新激活尝试必须生成新的 Quota 操作 ID，以重新占用额度。

## Tenant 切换

Tenant Context Switch 的根服务是 IAM：`POST /api/v1/auth/tenant-switches` 只通过必需的 host-only、HttpOnly Refresh Token Cookie 定位当前 Refresh Token Family，并继续要求受控 Origin、Fetch Metadata 与 `X-SF-CSRF`；Bearer Access Token 不作为该操作的会话定位凭据。请求 Body 只接受目标 `membershipId`。只有已经具有 Tenant Context 的 `USER_TENANT` Family 可以切换；Platform 会话必须重新以 `TENANT` 意图登录，`USER_TENANT_SELECTION` 继续使用首次上下文选择流程，首次改密会话不得切换。

IAM 从 Family 取得 `identityId`，使用 IAM 保留服务 Client 的有效 Service Access Token 与精确 `tenant-access:membership:read` Scope，依次调用 Tenant Access 的 [Membership Validation v1](../saas-forge-contracts/saas-forge-protobuf-contracts/tenant_access/membership/v1/membership_validation.proto) 验证当前与目标 Membership，不新增切换专用 RPC。Tenant Access 只在 Membership 属于该 Identity、仍启用且所属 Tenant 当前可访问时返回权威 `membershipId`、`tenantId`，否则返回无原因拒绝；允许结论不得缓存。当前 Membership 不可用时 IAM 撤销该 Family 及其全部未过期 Token、清除 Cookie 并返回 `403 / ACCESS_CONTEXT_UNAVAILABLE`；目标 Membership 不可用时返回同一无原因 `403`，但保留当前会话。Tenant Access 不可用或响应非法时返回 `503 / TENANT_ACCESS_UNAVAILABLE`，不改变当前会话。

每次请求以 `(familyId, Idempotency-Key)` 唯一标识并绑定目标 Membership：同 Family、同 Key、同目标稳定重放，同 Key 改变目标返回冲突，其他 Family 使用相同 Key 是独立请求。目标就是当前 Membership 时返回无副作用的稳定 `204 No Content`，不撤销 Token、不更新 Family，也不发布切换事件。实际切换时，IAM 先按照 [ADR 0029](adr/0029-revocations-use-a-durable-fact-and-synchronous-redis-index.md) 将该 Family 切换前签发且未过期的全部 User Access Token 写入 Redis Revocation Index，再在一个 IAM 数据库事务中持久化相同 `jti` 的撤销事实、更新 Family 上下文、记录稳定 `204` 结果、标记等待 Refresh，并写入 `com.saas.forge.iam.tenant-context-switched.v1` Outbox。其他 Family 不受影响，切换接口不返回或持久化原始 Token。

实际切换后，原请求同 Key 重放 `204`；客户端成功调用既有刷新接口取得目标 Tenant Token 前，其他切换返回 `409 / TENANT_CONTEXT_SWITCH_REFRESH_REQUIRED`。Switch 与 Refresh 对同一 Family 串行化并校验上下文版本；Refresh 在提交前发现 Family 已变化时不得保存已准备的 Token，也不得消费或轮换 Refresh Token。刷新时目标 Membership 已失效则撤销 Family 并要求重新登录；成功刷新只解除等待状态，不发布第二个切换事件。

IAM 在第一次调用 Tenant Access 前持久化 Family 级根工作流，同一 Family 同时最多存在一个未终结切换。暂时失败返回 `503 / TENANT_CONTEXT_SWITCH_PENDING` 与 `Retry-After`，Worker 和同 Key 重试恢复同一流程，其他 Key 返回 `409 / TENANT_CONTEXT_SWITCH_IN_PROGRESS`。自动恢复默认最多尝试 10 次并可配置；耗尽后持久化时间与脱敏失败摘要，原 Key 稳定返回 `409 / TENANT_CONTEXT_SWITCH_RETRY_REQUIRED`，解除 Family 在途限制并允许新 Key 重试。Redis 已写入而数据库未提交时允许旧 Token 提前失效，但 Family 保持原上下文，额外拒绝不回滚。

## Tenant Closure

Tenant Access 拥有 Tenant Closure 根工作流；先由 IAM 完成 Tenant 范围会话及有效 `jti` 撤销，再提交不可逆 `CLOSED`。IAM 或本地提交失败时继续恢复原持久工作流，不以中断为由解除已建立的访问阻断，也不提供从 `CLOSED` 恢复访问的操作。既有生命周期在途冲突须沿用同 Tenant 串行化边界，不能直接跳过正在执行的初始化或冻结。

关闭不删除成员、业务或审计记录，不自动取消 Subscription，也不批量清除品牌素材；素材按独立禁用与生命周期策略处理。已有冻结 Fence 的衔接、幂等与错误码须在本切片契约评审中冻结，本节不表示关闭 API 已实现。

## 成员禁用与 Tenant Suspension

成员禁用和 Tenant Suspension 的根服务均为 Tenant Access。Tenant Access 对 Tenant 生命周期和该 Tenant 下的成员禁用共同按 `tenantId` 串行化，并为每个根工作流持久化 `sessionRevocationId`；成员禁用另持久化 `quotaReleaseOperationId`。Tenant Access 使用精确 `iam:sessions:write` Scope 调用 IAM 内部 `RevokeUserSessions`，请求以 UUIDv7 `revocationRequestId` 幂等并以 `oneof MembershipTarget { membershipId, tenantId } | TenantTarget { tenantId }` 表达撤销目标，不得同时或全部缺失。IAM 在扫描会话前先建立该目标的 Revocation Fence，使用户 Token 签发路径和 Gateway 立即拒绝该范围，然后撤销匹配的 Refresh Token 和全部仍有效 Access Token `jti`。Tenant Fence 覆盖整个 Tenant 并优先于 Membership Fence；IAM 发现同 Tenant 已有 Tenant Fence 时拒绝新 Membership Fence，不静默合并工作流或计数。IAM 按 `revocationRequestId` 稳定重放撤销结果；响应只包含被撤销的 Family 与 `jti` 数量，不返回 Token、摘要或会话明细。IAM 成功后 Tenant Access 才能提交自己的领域变更。

Tenant Suspension 和恢复分别使用 Tenant Access 耐久根工作流。外部 `(actorIdentityId, Idempotency-Key)` 绑定 HTTP 方法、Tenant 与动作，工作流在首次远程调用前持久化独立 UUIDv7 内部请求 ID。同 Key 的相同请求在处理中返回 `503` 与 `Retry-After`，完成后稳定重放 `200` 及 Tenant 响应；同 Key 改变指纹返回 `409 / IDEMPOTENCY_KEY_REUSED`。同 Tenant 另一 Key 在未终结工作流存在时返回 `409 / TENANT_LIFECYCLE_CHANGE_IN_PROGRESS`。外部 Key 不跨服务传递。

IAM 在 Fence 下以可配置的有界批次处理无上限的 Tenant 会话：每批先幂等写入 Redis `jti` 撤销索引，再提交该批 Family/Issuance 撤销事实、稳定游标与累计数量。中途失败不回滚已完成的额外拒绝，同一 `revocationRequestId` 从持久游标恢复；只有全部批次完成后才标记请求成功并发布唯一 `com.saas.forge.iam.sessions-revoked.v1`。不得使用无上限 Lua 参数或单一超大数据库事务。

`RevokeUserSessions` 不在一次 gRPC 中阻塞到整个 Tenant 撤销完成。每次调用最多协助推进一个有界批次，然后返回 `PENDING { retryAfterSeconds }` 或 `COMPLETED { revokedFamilyCount, revokedJtiCount }`；Tenant Access 始终使用同一 `revocationRequestId` 轮询，已完成请求稳定重放 `COMPLETED`。IAM Worker 通过数据库租约和 fencing token 独立推进 Fence、分页、撤销事实与最终事件；Tenant Access Worker 只推进自己的根工作流，轮询 IAM 并在完成后提交 Tenant 状态。两者都不依赖 HTTP 客户端重试，Tenant Access 不复制 IAM 分页进度，IAM 不修改 Tenant 领域状态。

内部 gRPC 使用稳定状态语义：非 UUIDv7、目标缺失/冲突或 MembershipTarget 两个 ID 不完整为 `INVALID_ARGUMENT`；Service Token 缺失或无效为 `UNAUTHENTICATED`；Client 或 `iam:sessions:write` Scope 不符为 `PERMISSION_DENIED`；Tenant/Membership Fence 冲突或释放代际不匹配为 `FAILED_PRECONDITION`；Redis、PostgreSQL 或必要恢复状态暂时不可用为 `UNAVAILABLE`。正常未完成始终返回业务 `PENDING`，不以 `DEADLINE_EXCEEDED` 表示工作进度。

批次大小、Worker 租约、轮询间隔与退避是按环境变化的非敏感运行策略，分别放入 IAM 和 Tenant Access 的应用专属 Nacos 资源且 `refreshEnabled=false`。实现必须校验正数、有界批次与 lease 大于单批最坏执行时间；除已确认的默认最多 10 次自动恢复外，其他环境数值必须先通过多批次 Testcontainers 夹具测量，不得臆造 staging/prod 值。

IAM 最终计数只包含本请求首次产生的撤销：`revokedFamilyCount` 是本请求从可用变为已撤销的 Family 数，`revokedJtiCount` 是本请求首次写入持久撤销事实的未过期 `jti` 数。已撤销、已过期或重复批次不增加计数；终值持久化后由同一 `revocationRequestId` 稳定重放。对外事件仍只包含已白名单化的 `revokedSessionCount`，不暴露 Token 级明细。

自动恢复默认最多尝试 10 次并允许配置。Fence 尚未建立时耗尽，原 Key 稳定返回 `409 / TENANT_SUSPENSION_RETRY_REQUIRED` 并允许新 Key 建立新根工作流。Fence 已建立后耗尽，Fence 保持生效且工作流进入 `RECOVERY_REQUIRED`，所有普通新 Key 都被拒绝；只有 Platform Admin 可以通过 `POST /api/v1/platform/tenants/{tenantId}/suspension-recoveries` 和新 Idempotency-Key 恢复原工作流。该操作不创建新 Fence 或撤销请求，不能取消 Fence、跳过剩余批次或直接提交 `SUSPENDED`。

目标匹配同时考虑 Family 当前上下文和 Access Token Issuance 历史上下文：当前 `USER_TENANT` Family 匹配目标 Membership/Tenant 时撤销整个 Family 及其全部未过期 `jti`；Family 已切换到其他上下文时保留 Family，但仍撤销其为目标上下文签发且未过期的历史 `jti`。`USER_PLATFORM`、`USER_TENANT_SELECTION` 和 `INITIAL_PASSWORD_CHANGE` Family 不因 Membership/Tenant 目标而被撤销。已撤销或已过期记录只用于幂等重放与稳定计数，不重复产生副作用。

成员禁用在 Tenant Access 的单一事务中写入 Membership 禁用事实、HTTP 幂等结果、`quotaReleasePending` 工作项和 `com.saas.forge.membership.disabled.v1`。提交后立即调 Entitlement `release(max_users, quotaReleaseOperationId)`；失败时 Membership 不恢复，工作项以同一 ID 持续重试。这样最坏情况只是额度暂未释放，而不会出现额度已释放但成员仍启用的窗口。

成员重新启用必须等待原禁用的会话撤销及额度释放全部完成，不允许新启用工作流与未完成禁用互相覆盖。重新启用使用独立持久根工作流与稳定计量 ID；扣减额度后的本地失败须持久化补偿，失败期间 Membership 保持禁用。Fence 解除只能针对已完成原撤销及匹配代际，不清除既有 Family 或 `jti` 撤销事实。具体操作契约与恢复状态须随本切片冻结。

Tenant Suspension 在 Tenant Access 的单一事务中完成 `ACTIVE → SUSPENDED`、HTTP 幂等结果和 `com.saas.forge.tenant.suspended.v1`。它不禁用 Membership、不释放 `max_users`，恢复为 `ACTIVE` 也不恢复已撤销会话；用户必须重新登录或重新切换 Tenant。恢复时 Tenant Access 使用 IAM 内部 `ReleaseUserSessionFence`：请求包含新 UUIDv7 `releaseRequestId`、原始 `revocationRequestId` 和同一强类型目标。IAM 只解除由该原始撤销请求建立的 ACTIVE Fence；同一释放请求稳定重放，Fence 已被后续撤销请求替代时必须拒绝，绝不删除新 Fence。IAM 确认对应批量撤销已完成并解除 Fence 后，Tenant Access 才提交 `SUSPENDED → ACTIVE`；解除 Fence 不得清除任何 Family 或 `jti` 撤销事实。如果本地恢复提交失败，Tenant 仍为 `SUSPENDED`，同一请求可幂等重试。对已为 `SUSPENDED` 的 Tenant 以新 Key 请求 Suspension，或对已为 `ACTIVE` 的 Tenant 以新 Key 请求恢复，都返回 `409 / TENANT_STATE_TRANSITION_NOT_ALLOWED` 且不调用 IAM；原始 Key 始终重放当时持久化的成功响应，不因后续状态变化改写历史结果。IAM 的成功会话撤销可独立发布 `com.saas.forge.iam.sessions-revoked.v1`，即使后续 Tenant Access 本地提交失败也只表示已发生的安全事实。
