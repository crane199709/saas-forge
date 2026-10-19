# Tenant Context Switch 以 Refresh Token Family 为会话边界

> 适用范围：下文是现行 v1 的切换协议。#201/#202 的单 Console 将允许同一普通会话在平台与公司之间切换，见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md)（已确认、未实施）。仅 Tenant 间切换及重新以 TENANT 意图登录不再是新版目标；当前权限失效结束会话、权威核验、同 Family 撤销与可恢复提交继续保留。

Tenant Context Switch 只通过受控浏览器携带的 host-only、HttpOnly Refresh Token Cookie 定位当前 Refresh Token Family，并同时要求 Origin、Fetch Metadata 与 `X-SF-CSRF` 校验；Bearer Access Token 不作为该操作的会话定位凭据。只有已经具有 Tenant Context 的 `USER_TENANT` Family 可以切换，且其当前 Membership 与目标 Membership 在调用时都必须是 Accessible Membership；当前授权关系已失效时，IAM 终止该 Family 并要求重新登录，不允许借切换进入另一个 Tenant。Platform 会话必须重新以 `TENANT` 意图登录，`USER_TENANT_SELECTION` 继续使用首次上下文选择流程，首次改密会话不能切换。IAM 通过现有 v1 `ValidateMembership(identityId, membershipId)` 依次验证当前和目标 Membership，不新增切换专用 RPC；两次调用都使用 IAM 保留服务 Client 的精确 `tenant-access:membership:read` Scope，不缓存允许结果，依赖失败时不建立新上下文。Cookie 无效、Family 过期或类型错误时返回 `401` 并清除 Cookie；当前 Membership 不可用时返回 `403 ACCESS_CONTEXT_UNAVAILABLE`，撤销该 Family 及其全部未过期 Token 并清除 Cookie；目标 Membership 不可用时返回同一无原因 `403`，但保留当前会话；Tenant Access 不可用或响应非法时返回 `503 TENANT_ACCESS_UNAVAILABLE`，不改变当前会话。目标就是当前 Membership 时返回无副作用的稳定 `204 No Content`，不撤销 Token、不修改 Family，也不发布切换事实。IAM 从该 Family 取得 Identity，实际切换成功后使该 Family 先前签发且仍未过期的全部 User Access Token 不再可用，再由客户端通过既有刷新接口取得新 Tenant Context 的 Token；同一 Identity 的其他 Refresh Token Family 不受影响。实际切换后，Family 在客户端成功刷新并取得目标 Tenant Token 前不接受其他切换：原请求同 Key 稳定重放 `204`，其他请求返回 `409 TENANT_CONTEXT_SWITCH_REFRESH_REQUIRED`；刷新时目标 Membership 已失效则终止该 Family。Switch 与 Refresh 对同一 Family 的上下文变更必须串行化并校验上下文版本；Refresh 在提交前发现 Family 已变化时，不得保存已准备的 Token，也不得消费或轮换 Refresh Token，客户端按最新上下文重试。每次请求以 `(familyId, Idempotency-Key)` 唯一标识并绑定目标 Membership：同一 Family、同一 Key、同一目标稳定重放原结果，同一 Key 改变目标返回冲突，其他 Family 使用相同 Key 是独立请求。这样可以避免 Access Token 与 Cookie 来自不同会话时产生歧义，也不会把单个 `jti` 误当成浏览器会话本身。

IAM 在第一次调用 Tenant Access 前持久化 Family 级根工作流；同一 Family 同时最多存在一个未终结切换。暂时失败返回 `503 TENANT_CONTEXT_SWITCH_PENDING` 与 `Retry-After`，后台 Worker 和同 Key 重试恢复同一流程，其他 Key 在其终结前返回 `409 TENANT_CONTEXT_SWITCH_IN_PROGRESS`；恢复成功后进入等待客户端 Refresh 的状态。

自动恢复默认最多尝试 10 次并允许通过 IAM 配置调整；耗尽时持久化时间与脱敏失败摘要，原 Key 稳定返回 `409 TENANT_CONTEXT_SWITCH_RETRY_REQUIRED`，同时解除 Family 的在途限制，使客户端可用新 Key 重新发起。尚未提交的切换保持原 Family 上下文；Redis 已产生的额外 Token 拒绝不回滚。

本切片以 IAM 的持久撤销事实和 Redis Revocation Index 为撤销交付边界；Gateway 对旧 Token 的实际拒绝及 Redis 不可用时的验证失败关闭仍由独立 Gateway 切片完成，不能从本决策的 IAM 测试推断公网端到端已拒绝旧 Token。

实际切换在更新 Family 上下文、持久化全部旧 `jti` 撤销事实、记录稳定 `204` 结果并进入等待 Refresh 状态的同一数据库事务中写入 `com.saas.forge.iam.tenant-context-switched.v1` Outbox；后续 Refresh 只签发新上下文 Token 并解除等待状态，不发布第二个切换事件。

本切片的完成证据必须包含真实 IAM↔Tenant Access gRPC、保留服务 Client 与精确 Scope、PostgreSQL 18 权威 Membership 查询，以及 IAM PostgreSQL/Redis 撤销与 Refresh 闭环；Mock 只用于超时、非法响应、提交失败和重试耗尽等故障注入。Gateway 与浏览器不属于本切片验收。

本决策直接改造已冻结的 `POST /api/v1/auth/tenant-switches`，以 Cookie-only 会话定位取代基线中“Access Token 或 Refresh Token Cookie”的认证描述。该操作已在 IAM 的 `AuthenticationController` 中实现，并由 `iam_tenant_context_switches` 工作流迁移交付，不再落到生成接口的默认 `501`；覆盖仍然成立，因为该变更发生在契约冻结之后而历史基线快照保持不可修改，因此这是对 [ADR 0016](0016-v1-contracts-use-reviewed-repository-baselines.md) 的单操作显式覆盖，其余 v1 契约不获得兼容性豁免。

上面第 9、13 段记录的“Gateway 与浏览器不属于本切片验收”是当时的切片边界，后续切片已补齐：真实浏览器的 `tenant-switches:204 → refresh:200` 链路，以及撤销 Token、Redis 不可用、越权 Tenant 切换与 Tenant 冻结后旧 Token 拒绝等路径，见 [Stage 2 浏览器验收](../archive/32-stage2-browser-acceptance.md) 与 [Issue #115 验收记录](../acceptance/issue-115-console-authentication.md)。
