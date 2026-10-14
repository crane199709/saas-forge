# IAM Context

IAM 管理平台认证主体、凭据、会话、用户与服务 Token，以及 Platform 全局访问关系；它不拥有 Tenant、Membership 或业务资源。

## Language

**Identity**:
IAM 管理的全局认证主体，以唯一的规范化邮箱地址识别，并可有显示名；它独立于 Tenant，也不等同于任何 Membership。
_Avoid_: User, account, tenant user

**Default Platform Admin**:
首次部署时由 IAM 显式部署引导任务创建的全局 Identity，绑定 Platform Role 而非任何 Tenant Membership。
_Avoid_: Default tenant administrator, superuser, tenant administrator

**Initial Platform Credential**:
仅用于首次设置 Default Platform Admin 密码的随机初始密码，在创建后 24 小时失效且不能执行 Platform 管理操作。
_Avoid_: Platform access credential, default password

**Initial Credential Session**:
Initial Platform Credential 验证成功后建立的受限浏览器会话，只证明该 Identity 可以完成首次改密，不建立 Tenant Context，也不是 User Access Token 会话。
_Avoid_: Platform admin session, user access token

**Credential**:
由 IAM 管理、供一个 Identity 证明其认证能力的凭据。MVP 仅有 `INITIAL_PLATFORM_PASSWORD` 与 `PASSWORD` 两种类型。
_Avoid_: API key, external identity

**Password Credential**:
一个 Identity 用于常规密码认证的 `PASSWORD` Credential；IAM 只持久化其 Argon2id 哈希，绝不保存原始密码。
_Avoid_: Password, shared secret

**Password Setup Challenge**:
IAM 为从未拥有任何 Credential 的 Identity 签发的一次性、限时密码建立凭据；它不能用于重置、替换或绕过既有凭据的恢复流程。
_Avoid_: Password reset token, invitation token, initial password

**Password Recovery**:
已有 Password Credential 的 Identity 在无法继续使用当前凭据时，通过独立验证流程替换该凭据的恢复过程。
_Avoid_: Password Setup, Invitation activation, initial password

**User Access Token**:
IAM 为已认证 Identity 签发的短期用户访问凭证；它可以表示 Platform 全局身份，或成对绑定一个已验证的 Membership 与 Tenant Context。
_Avoid_: Refresh Token, service token, role token

**Access Token Issuance**:
IAM 对一个已签发 User Access Token 保存的非敏感生命周期索引，使安全事件可以定位并撤销尚未到期的 `jti`；它不是 Token 本身。
_Avoid_: Access token store, session token

**Revocation Index**:
IAM 将尚未到期的 Access Token、Signing Key 与 OAuth Client 撤销事实投影到 Redis 后形成的即时拒绝索引；只有索引已完成同步时，验证方才可依赖它放行。
_Avoid_: Authorization database, token introspection

**Revocation Fence**:
IAM 为一个 Membership 或 Tenant 建立的安全边界，使目标范围在批量会话撤销期间不能签发或使用新的 User Access Token。
_Avoid_: Tenant Suspension, Membership status, jti blacklist

**Login Context Intent**:
旧版浏览器认证中，登录前对 Platform 或 Tenant 工作上下文的显式选择；它不携带或证明任何 Role、Membership 或 Tenant 身份，也不等同于统一登录后的工作上下文选择。
_Avoid_: Tenant selection, inferred login origin

**Unified Console Session**:
统一 Console 中绑定一个 Identity 和至多一个当前工作上下文的浏览器会话；同一浏览器的所有 Console 标签页共享它的身份、工作上下文与结束状态。
_Avoid_: Platform session plus tenant session, account switch, global identity session

**Available Work Context**:
一个 Identity 当前可选择的平台管理或公司工作范围，分别以 Platform Role 或 Accessible Membership 为授权依据；它是候选资格，不是后续操作的授权承诺。
_Avoid_: Menu permission, login intent, tenant operation target

**Unselected Console Session**:
已完成常规凭据认证但尚未建立工作上下文的统一会话，可因没有候选而等待授权，也可等待选择已有候选；它不授予业务访问权限，也不等同于 Initial Credential Session。
_Avoid_: Anonymous session, initial credential session, platform session

**Platform Role**:
在 Platform 全局范围内授予权限的角色，与只在单个 Tenant 内生效的 Tenant Role 相互独立。
_Avoid_: Tenant role, membership role

**JWT Signing Key**:
用于以 `RS256` 签发 Access Token 的非对称签名密钥；每个版本以唯一 `kid` 标识，生产私钥由 KMS/HSM 保管且不可导出。
_Avoid_: JWT secret, shared signing secret

**Signing Key Metadata**:
IAM 对一个 JWT Signing Key 版本保存的公开标识、KMS/HSM Key Version 引用、JWKS 公开材料与生命周期记录，不包含私钥。
_Avoid_: Private key, JWT secret

**Revoked Signing Key**:
已被判定疑似泄露、不得再用于签发或验证 Access Token 的 JWT Signing Key 版本。
_Avoid_: Retired signing key, expired signing key

**Refresh Token Family**:
为同一浏览器会话连续签发并轮换的 Refresh Token 谱系，持有该会话的 Identity、Purpose 及相应上下文。
_Avoid_: Access token, browser cookie

**Refresh Token Family Context Version**:
Refresh Token Family 上 Purpose、Membership 与 Tenant 上下文变化的单调版本；普通活动与 Token 轮换不改变它。
_Avoid_: Last used time, token version, refresh count

**Refresh Rotation Lease**:
IAM 为同一旧 Refresh Token 的一次在途轮换建立的短期并发边界；它不是会话状态，也不延长 Token 或 Family 生命周期。
_Avoid_: Refresh token lock, session lease

**OAuth Client**:
由 IAM 注册、以 `client_id` 和显式 Scope 表示的服务认证主体；它不代表用户、Membership 或 Tenant Context。
_Avoid_: User, service tenant identity

**OAuth Client Type**:
决定一个 OAuth Client 的创建责任与可授予 Scope 集合的分类；MVP 只有 `RESERVED_SERVICE` 与 `RUNTIME_SERVICE`。
_Avoid_: Role, tenant type, arbitrary scope group

**Service Access Token**:
IAM 通过 Client Credentials 为 OAuth Client 签发的短期访问凭证，只表示 `client_id` 与显式 Scope，不建立 Identity、Membership 或 Tenant Context。
_Avoid_: User Access Token, tenant token, service tenant identity

**Client Secret**:
OAuth Client 的机器生成认证机密，仅在创建或轮换时明文展示一次；IAM 只保存其 SHA-256 摘要。
_Avoid_: Password, API key

**Client Secret Issuance Recovery**:
Secret 签发响应遗失后，由原操作者在十分钟内创建的一次显式替代操作；它不恢复、保存或再次展示既有明文 Secret。
_Avoid_: Secret retrieval, Secret replay, encrypted Secret storage

**OAuth Client Revocation**:
Platform Admin 对一个 OAuth Client 执行的不可逆终止；从提交起，该 Client 不能再签发或使用 Service Access Token。
_Avoid_: Client Secret rotation, Client suspension, token expiry

**Reserved Service Client Replacement**:
受控部署流程为已吊销的 `RESERVED_SERVICE` Client 创建新 Client ID 与固定 Scope 的替代过程；它不复活旧 Client。
_Avoid_: Client reactivation, Client Secret rotation, runtime Client creation
