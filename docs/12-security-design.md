# saas-forge 安全设计

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

> **认证协议边界（2026-10-14）**：下文 Login Context Intent、双 Browser Session Slot、`sessionSlot` 参数及只撤销当前 `jti` 的普通登出描述属于 v1 历史协议。受控启用 v2 后，按 [ADR 0052](adr/0052-unified-console-authentication-uses-versioned-session-protocol.md) 与[统一认证协议](unified-authentication-protocol.md)执行单一统一会话、全部相关 `jti` 撤销及无候选等待会话。第 4 阶段新邀请浏览器闭环以 v2 为前置，不由实现自动切换环境开关；非协议专属的安全约束继续有效。

## 合规与安全基线

首期以中国境内的个人信息保护法、数据安全法、网络安全法和等保定级评估为基线。最终等保等级必须在实施前定级，并按实际定级结论建设；不预设具体等级。

无论最终等级如何，MVP 必须覆盖身份鉴别与访问控制、网络边界与通信安全、主机/应用/数据安全、安全审计与集中监测、备份恢复与灾难恢复、漏洞/变更/应急响应，以及安全制度、责任人和操作记录。数据出境、GDPR、医疗、金融和支付合规仅在实际业务、数据类别和运营地区触发时纳入。

## 身份认证与会话

### 用户令牌

- Access Token 默认有效期为 15 分钟，可由环境配置覆盖且生产不设置硬上限；`iat` 到 `exp` 精确等于配置值。它是以 `RS256` 签名的 JWT，除 `iss`、`aud`、`iat`、`exp` 等验证所需标准 Claim 外，只包含 `identityId`、`jti`，以及成对出现的可选 `membershipId`、`tenantId`；Gateway 与 SDK 以 IAM JWKS 本地验签，且只接受 `RS256`。验证端允许 30 秒时钟偏差，JWT `jti` 黑名单 TTL 必须覆盖 Token 剩余有效期及该偏差。
- `jti` 使用规范小写、连字符形式的 UUIDv7，每次实际签发新的 Access Token 都生成新值，不在登录、正常刷新或恢复刷新之间复用，也不由数据库自增值、Family ID 或 Membership ID 派生。Redis 只使用 `SHA-256(jti)` 构造撤销 Key。恢复刷新必须撤销前一次响应的 `jti` 至其 `exp` 加 30 秒，并为替代 Access Token 生成新 `jti`。
- IAM 为每个 User Access Token 持久化最小 Access Token Issuance，包含 `jti`、`familyId`、`identityId`、可选 `membershipId`/`tenantId`、`kid`、`issuedAt`、`expiresAt`、`revokedAt` 和撤销原因，不保存 JWT、签名、邮箱、角色或权限。Family 创建或轮换与对应 Issuance 在同一数据库事务提交；常规请求不查询该表。Family 重放或安全事件据此撤销全部匹配且未过期的 `jti`，记录至少保留至 `expiresAt + 30 秒`；普通登出仍只撤销请求携带的当前 Access Token。
- PostgreSQL 中的撤销时间是持久化事实，Redis Revocation Index 是 Gateway 与 Starter 的同步拒绝投影。撤销操作先以 Spring Data Redis 和 Lua 原子写入全部目标 `jti`/`kid` Key，再提交 PostgreSQL 事务，二者完成后才返回成功；Redis 失败时不提交，数据库提交失败时允许 Redis 产生安全优先的额外拒绝。IAM 启动、Redis 重连或数据恢复时先将 `revocation-index-ready` 标为未就绪，从 PostgreSQL 重建全部未过期撤销项，完成后才标为就绪；验证方每次同时检查 Ready 状态和目标 Key，Ready 缺失时 fail-closed。安全 Key 使用 AOF 与 `noeviction`，写入和重建均须幂等。
- `iss` 由每个环境显式配置为该环境的 API Origin，例如本地环境为 `https://api.saas.forge.test`；`aud` 固定为单值 `saas.forge-api`。IAM、Gateway、SDK 和业务服务必须精确校验两者，不接受多 Audience、通配值或缺失值，也不得从 `Host`、`Forwarded` 或 `X-Forwarded-Host` 等请求头动态推导签发方。
- Refresh Token 是由 CSPRNG 生成的 256 位随机不透明字符串，浏览器仅以 HttpOnly Cookie 发送；PostgreSQL 只保存其 SHA-256 摘要并作为权威记录，Redis 可缓存会话状态。每个 Token 归属一个稳定的 Refresh Token Family，自首次登录起最长有效 8 小时、空闲最长 30 分钟；轮换不延长绝对期限。IAM 仅在成功登录或刷新时更新 Family 的 `lastUsedAt`。已轮换 Token 的摘要必须保留至所属 Family 的绝对到期，此前不得物理清理；轮换后再次提交旧 Token 时，IAM 必须撤销整个 Family。
- 刷新开始前，IAM 按旧 Token 摘要在 Redis 原子取得默认 5 秒的 Refresh Rotation Lease，并将 Lease 绑定本次 `Idempotency-Key`。同一旧 Token 以不同幂等键在 Lease 存续期间到达时返回 `409 / REFRESH_ROTATION_IN_PROGRESS` 与 `Retry-After`，不签发 Token、也不撤销 Family；Lease 到期后，旧 Token 的同键重试按 10 秒单次恢复语义处理，不同键重放撤销整个 Family。Lease 只抑制在途并发，不是会话或撤销权威状态，Redis 不可用时 fail-closed。
- 普通会话每次设置所选 Browser Session Slot 的 Refresh Token Cookie 时，`Max-Age` 等于 30 分钟与 Family 绝对剩余时间中的较小值；成功刷新可重置空闲期限，但不能延长 8 小时绝对期限。Initial Credential Session 固定使用 Platform 槽位，Cookie 最多有效 10 分钟且不得超过 Initial Platform Credential 的剩余有效期，改密不延长它。PostgreSQL Family 状态始终是权威；登出或失效时以所选槽位相同名称、Path 和安全属性设置 `Max-Age=0` 清除 Cookie。
- Tenant Context Switch 只使用 Tenant Browser Session Slot 的 `__Host-sf_tenant_refresh` Cookie 定位当前 `USER_TENANT` Family，并要求既有受控 Origin、Fetch Metadata 与 `X-SF-CSRF` 校验；Bearer Access Token 不作为会话定位凭据。IAM 同步校验当前与目标 Membership 后，先将该 Family 切换前签发且未过期的全部 User Access Token 写入 Redis Revocation Index，再原子提交持久撤销事实、Family 新上下文、稳定 HTTP 结果和 Outbox。其他 Family 和 Platform 槽位不受影响；目标就是当前 Membership 时为无副作用 `204`。实际切换后必须先通过 Tenant 槽位刷新取得新上下文 Token，客户端不得回滚到旧 Token；刷新完成前拒绝其他切换和业务请求，Switch 与 Refresh 对同一 Family 串行化并校验上下文版本。
- Refresh Token Family 的 Purpose 只能为 `USER_PLATFORM`、`USER_TENANT`、`USER_TENANT_SELECTION` 或 `INITIAL_PASSWORD_CHANGE`。普通刷新必须重新校验 Purpose 对应的权威上下文：Platform 会话确认 Identity 仍持有 Platform Role，Tenant 会话同步验证 Membership 仍可访问，待选择会话重新取得候选列表；Initial Credential Session 不允许刷新。权威服务不可用时不得消费或轮换 Token，并返回可重试的基础设施错误；权威结果明确拒绝时撤销 Family、清除 Cookie，且不得签发 Access Token。
- 用户请求不得通过请求头、查询参数、请求体或任何语义等价别名传入或覆盖 Tenant；这类输入必须以 `400` 拒绝，不能静默忽略。用户 Tenant Context 只能由已验证 Access Token 的 `membershipId` 解析。
- 普通登出以必填 `sessionSlot` 撤销所选 Refresh Token Family 并清除该槽位 Cookie；请求携带当前 Bearer Access Token 时，还必须将其 `jti` 写入 Redis 黑名单，TTL 为 Token 剩余有效期加 30 秒时钟偏差。没有 Access Token 的 Membership 待选择会话或 Initial Credential Session 仍可登出。普通登出不影响另一 Browser Session Slot 或该 Identity 的其他 Family；密码重置、成员禁用、Tenant 冻结、强制下线等安全事件按各自范围撤销匹配会话及尚未到期的 `jti`。
- Gateway 与 SDK 每次用户请求检查黑名单；Redis 不可用时 fail-closed，避免已失效 Token 被继续接受。
- 按 Membership 或 Tenant 批量撤销时，IAM 必须在扫描已签发 Token 前建立持久 Revocation Fence 并同步投影到 Redis。用户 Token 签发路径与 Gateway 都必须拒绝 Fence 覆盖的 Membership/Tenant；IAM 只有在 Fence 下阻止新 Token 提交并撤销全部既有未过期 `jti` 后才能报告成功。Fence 不代替 PostgreSQL 撤销事实或逐 `jti` Redis 索引；Tenant 恢复时只能在既有 Token 已不会复活的前提下解除 Fence。Redis 不可用、Fence 状态不可确定或 Revocation Index 未就绪时，签发与验证都 fail-closed。
- Revocation Fence 的 PostgreSQL 记录是权威，包含目标、原始撤销请求、状态与建立/解除时间。Redis 中的 ACTIVE Fence 不设 TTL，只能由验证过的解除流程删除；Redis 启动、重连或恢复时与未过期 `jti`/`kid` 一起在 Ready=false 期间从 PostgreSQL 重建。Fence Key 可使用规范 UUIDv7 Membership/Tenant ID 作为目标标识，但 Key、值、日志和诊断不得包含 Token、邮箱或其他机密。
- Redis Fence Key 固定为 `sf:<environment>:iam-service:user-session-revocation-fence:v1:<target_type>:<target_uuid>`，`target_type` 只允许 `membership` 或 `tenant`，值为建立 Fence 的规范 UUIDv7 `revocationRequestId`。IAM 是唯一写入者，IAM、Gateway 与 Starter 为读取者；解除时必须通过 Lua 比较 `revocationRequestId` 后条件删除，不得无条件删 Key。
- Tenant 批量撤销不依赖未受限的 Redis Lua 调用或单个超大数据库事务。IAM 在 Fence 下分批执行，每批仍保持 Redis 先于 PostgreSQL，并持久化可恢复游标；全部批次完成前不报告整体成功。对当前上下文匹配的 `USER_TENANT` Family 撤销整个 Family 及其全部未过期 `jti`；对已切换离开目标上下文的 Family 仅撤销历史上为目标签发且未过期的 `jti`。
- Gateway 以正式 OpenAPI `security` 声明作为用户 Token 强制策略来源：必需 `UserBearerAuth` 的操作必须完成验签、`jti`/`kid`、Revocation Fence 和 Index Ready 检查后才转发；匿名操作不要求 User Token。登出等将 `UserBearerAuth` 与匿名并列的可选 Token 操作，不得因 Bearer 缺失、无效或已撤销而阻止 Refresh Cookie 清理。路由安全分类必须参与 OpenAPI/Gateway 一致性门禁，不得使用可漂移的独立手写列表。
- Gateway 在必需 User Token 操作上对缺失、签名/时间/Claim 无效、`jti`/`kid` 撤销或 Revocation Fence 命中统一返回 `401 / ACCESS_TOKEN_INVALID` 和 `WWW-Authenticate: Bearer`，不暴露具体撤销机制。Redis 不可用或 Index Ready/Fence 状态无法确定时返回 `503 / TOKEN_REVOCATION_STATUS_UNAVAILABLE`，不带 `WWW-Authenticate`。安全撤销先于 Tenant 领域访问状态；`403 / TENANT_SUSPENDED` 只适用于仍然有效的认证上下文到达 Tenant 权威校验后被拒绝的场景。
- `SERVICE_REQUIRED` 操作同时由 Gateway 与接收端 Starter 校验 Service Token。缺失或无效 Token 返回 `401 / ACCESS_TOKEN_INVALID`；合法 Token 缺少任一要求 Scope 返回 `403 / ACCESS_TOKEN_SCOPE_INSUFFICIENT`；撤销状态不可确定或验证上游不可用返回 `503 / TOKEN_REVOCATION_STATUS_UNAVAILABLE`。多个要求 Scope 按 AND 语义校验，已登记且已授予的额外 Scope 不构成拒绝原因。
- Gateway 在转发前删除所有平台保留的 Identity、Membership、Tenant、Role、Permission、Scope 与 Client 身份请求头，只能从已验证 Principal 重建允许的内部头；Starter 对绕过 Gateway 的直连请求再次拒绝外部伪造保留头。精确头清单、Route Catalog 与双重校验规则见 [Gateway Service Scope 路由设计](23-gateway-service-scope-routing.md)。

Service Access Token 使用与 User Access Token 相同的环境 Issuer、固定 `saas.forge-api` Audience、`RS256` Signing Key 生命周期与 30 秒时钟偏差，但 JOSE `typ` 固定为 `at+jwt`，默认有效期为 5 分钟。其 Claim 白名单固定为 `iss`、`aud`、`iat`、`exp`、`jti`、`sub`、`client_id` 与 `scope`：`sub` 必须等于 `client_id`，`scope` 使用去重并排序后的单空格分隔字符串，`jti` 每次实际签发均生成新的 UUIDv7。Service Access Token 不得包含 `identityId`、`membershipId`、`tenantId`、Role 或 Permission；接收方必须同时校验 Token 类型、签名、Issuer、Audience、时间、`client_id` 与操作所需的精确 Scope。

Refresh Token 只能由 `https://api.<root>` 以两个 Browser Session Slot 的 host-only Cookie 签发和接收：Platform 为 `__Host-sf_platform_refresh`，Tenant 为 `__Host-sf_tenant_refresh`；两者均固定 `Secure; HttpOnly; SameSite=Strict; Path=/` 且不设置 `Domain`。不得把 Access Token 或 Refresh Token 写入 `localStorage`、`sessionStorage` 或 IndexedDB；只允许持久化不敏感的槽位单调代次与 `logoutPending`。Platform Console 与 Tenant Console Shell 可在受控跨 Origin 请求中携带 Cookie，但不能读取它们。

浏览器交付仅使用同一完全受控可注册根域下的固定 HTTPS Origin：`https://platform.<root>`（Platform Console）、`https://console.<root>`（Tenant Console Shell）、`https://api.<root>`（API Gateway）和 `https://remote.<root>/<module>/<version>`（业务 Remote）。业务 Remote 不得使用任意外部域名；Cookie、CSRF 与 CORS 只信任这些已登记的 Origin。

浏览器的所有非安全方法和登录、刷新、登出请求必须使用 `application/json` 并带 `X-SF-CSRF: 1`。Gateway 只接受 `Origin` 精确为 `https://platform.<root>` 或 `https://console.<root>` 的此类请求，拒绝 `remote.<root>` 和外站 Origin，并拒绝 `Sec-Fetch-Site: cross-site`；缺失 Fetch Metadata 时仍要求 Origin 精确匹配。IAM 另外校验 `platform.<root> ↔ PLATFORM`、`console.<root> ↔ TENANT` 以及槽位与 Family Purpose；该配对不代替 Role 或 Membership 权威验证。任一来源安全失败对外统一为 `403 / BROWSER_REQUEST_REJECTED`。Client Credentials 服务请求不携带浏览器 Cookie，不适用该校验；Remote 只能通过 Shell 的共享类型化 HTTP Client 发起正式 operation。

每个环境由非敏感部署配置 `browser.rootDomain` 推导固定 CORS 值。API Gateway 只对 `https://platform.<root>` 与 `https://console.<root>` 返回凭据型 CORS 许可，允许 `GET`、`HEAD`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS` 以及 `Authorization`、`Content-Type`、`Idempotency-Key`、`X-SF-CSRF`、`traceparent`、`tracestate`；只暴露 `Location`、`Retry-After`，预检缓存 10 分钟并返回 `Vary: Origin`。Remote 静态资源只允许 `https://console.<root>` 无凭据加载。禁止通配符、`null` Origin 与 Manifest/运行时修改白名单。

开发与端到端测试也必须验证相同安全边界：`platform.saas.forge.test`、`console.saas.forge.test`、`api.saas.forge.test` 与 `remote.saas.forge.test` 映射至 `127.0.0.1`，由本地受信 TLS 反向代理提供 HTTPS，并设置 `browser.rootDomain=saas.forge.test`。不得以不同 `localhost` 端口替代此验收拓扑。

### 密码、邀请与服务身份

- 用户以全局唯一、规范化的 ASCII 邮箱地址登录；Identity 的显示名可重复且可为空。输入先去除首尾空白，再以 `Locale.ROOT` 小写形式持久化并用于登录查找。Tenant 管理员初始化可选传入 1–200 字符的显示名，IAM 仅在新建 Identity 时写入，复用既有 Identity 时不得覆盖。MVP 不支持 EAI/国际化邮箱、公共注册、手机号登录、外部身份源、OIDC、SSO、LDAP 或第三方登录。
- 登录请求以可选 `contextType` 明确 Login Context Intent，枚举仅为 `PLATFORM`、`TENANT`，缺省为 `TENANT`，且不得携带 Tenant 或 Membership ID。`PLATFORM` 要求 Identity 持有 Platform Role 并建立 `USER_PLATFORM` 会话；`TENANT` 只按 Accessible Membership 建立 Tenant 或待选择会话，Platform Role 不改变该分支。Initial Platform Credential 始终进入首次改密流程。IAM 不得从 `Origin`、Host 或控制台类型推断 Login Context Intent；Platform Console 必须显式发送 `PLATFORM`。
- 受控 Console 的共享登录页不向普通用户提供 Platform/Tenant 切换器；宿主固定注入 Intent。登录发现所选 Browser Session Slot 已指向可用 Family 时，在验证新密码前返回 `409 / SESSION_SLOT_ALREADY_ACTIVE`；切换账号必须先显式登出。
- 常规密码验证成功后，IAM 在 `TENANT` 登录分支从 Tenant Access 取得 Accessible Membership：恰有一个时直接建立对应 Tenant Context，多个时只建立待选择的 Refresh Token Family 并返回候选列表，没有 Accessible Membership 时以 `403 / ACCESS_CONTEXT_UNAVAILABLE` 拒绝且不创建 Refresh Token Family 或设置 Cookie。Platform Role 只用于显式 `PLATFORM` 登录，不能改变 `TENANT` 分支判断或代替 Membership；Platform Role Assignment 由 IAM 权威查询，不写入 Access Token，也不能用 Credential 类型代替。
- Membership 候选按 `tenantDisplayName`、`membershipId` 稳定排序，登录响应最多返回 100 条；Tenant Access 可多取第 101 条只用于判断超限。超过 100 条时返回 `409 / ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED`，不创建 Family 或设置 Cookie；该限制只约束无分页的登录候选响应，不限制 Identity 实际拥有的 Membership 数量，未来确有需求时另行设计受会话保护的分页选择 API。
- 邮箱不存在、密码错误、没有有效 Credential、Initial Platform Credential 已过期或认证主体被临时锁定时，对外统一返回 `401 / AUTHENTICATION_FAILED`，且账户锁定不返回 `Retry-After`；邮箱不存在或没有 Credential 时仍执行固定 Dummy Argon2id 校验，避免快速失败形成可枚举的时间差。密码已验证但缺少请求上下文所需的 Platform Role 或 Accessible Membership 时，v1 旧登录分支返回 `403 / ACCESS_CONTEXT_UNAVAILABLE`；受控启用的 v2 则保留无可用工作上下文的受限等待会话，不签发业务 Access Token。Redis 登录保护不可用时 fail-closed，返回 `503 / AUTHENTICATION_PROTECTION_UNAVAILABLE`。内部日志和审计可记录稳定失败原因，但不得记录原始邮箱、密码或 Token。
- 登录保护按规范化邮箱的 SHA-256 摘要计数，不存在的邮箱使用相同路径。默认在 15 分钟窗口内累计 5 次凭据失败后锁定 15 分钟；第 5 次及锁定期间仍返回统一 `401`，锁定期间不继续累计或延长锁定。一次成功密码验证立即清除失败计数，即使后续因上下文或依赖服务未建立会话；`403` 上下文拒绝、字段校验和基础设施错误不计入。阈值、窗口和锁定时间可由环境以正值覆盖且生产不设置硬上限；MVP 不引入 CAPTCHA、指数锁定或永久锁定。
- 密码在设置、修改和登录验证前统一执行 Unicode NFC 规范化；不修剪、不折叠、不静默截断，并拒绝普通空格、制表符、换行、全角空格、不换行空格等全部 Unicode 空白字符。规范化后的长度按 Unicode Code Point 计算，范围为 12–128，UTF-8 最多 512 字节；不强制大小写、数字或特殊字符类别组合。Blocklist 比较与 Argon2id 哈希（`m=19456 KiB`、`t=2`、`p=1`）使用同一规范化结果。边界失败使用 `PASSWORD_TOO_SHORT`、`PASSWORD_TOO_LONG` 或 `PASSWORD_WHITESPACE_NOT_ALLOWED` 字段错误，不得记录或回显密码。
- 设置或修改密码时使用应用制品内置的版本化 Blocklist，首版覆盖最常见的 100,000 个泄露或弱密码以及 SaaS Forge 相关预期值；不在请求路径调用外部在线密码检查服务。Blocklist 只保存完整密码的 SHA-256，不保存明文、不做子串或字典词拆分，并携带版本与校验和；生产环境发现文件缺失或损坏时拒绝启动。命中时返回字段错误 `400 / PASSWORD_COMPROMISED`，不得回显密码；列表随正式版本更新，不要求运行时联网热更新。
- 首版 Blocklist 固定取 SecLists 正式 Release `2026.1` 的 `Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt`，不得跟随 `master`。生成流程校验上游 SHA-256，对每行执行与密码相同的 NFC 规范化，合并仓库自有 SaaS Forge 弱密码后去重，并只把 SHA-256 结果打入运行时制品；制品保留来源版本、原始校验和与 MIT 许可证归属，不携带上游明文名单。常规构建不联网，升级必须显式提交来源版本、校验和与生成结果。
- Credential 仅有 `INITIAL_PLATFORM_PASSWORD` 与 `PASSWORD` 两种类型。一个 Identity 任意时刻最多有一个有效密码凭据；首次成功改密时，初始凭据永久失效但保留其失效记录，并新建常规密码凭据，不得覆盖或删除初始凭据。
- 有效 `INITIAL_PLATFORM_PASSWORD` 只能建立 Purpose 为 `INITIAL_PASSWORD_CHANGE` 的 Initial Credential Session：登录响应设置 Platform Browser Session Slot 的 `__Host-sf_platform_refresh` Cookie 并返回 `PASSWORD_CHANGE_REQUIRED`，但不签发 Access Token、不查询 Membership，刷新接口也不得把它升级为普通会话。该 Cookie 只允许调用登出和 `POST /api/v1/auth/password-changes`；改密事务永久失效 Initial Platform Credential、创建常规 Password Credential 并撤销该受限 Family，响应清除 Cookie、返回 `204`，用户必须用新密码重新登录。
- 首个 Platform Admin 由系统在首次部署时直接创建为全局 `Identity` 并授予 Platform 角色，不关联 Tenant `Membership`。其邮箱和随机初始密码仅由外部密钥管理系统注入；IAM 只保存密码的 Argon2id 哈希。初始密码自创建起有效 24 小时，期间只能建立完成改密所需的受限会话，不能调用 Platform 管理接口。首次成功改密后，初始密码永久失效并撤销关联会话；过期或疑似泄露时，只能由部署侧受限凭据执行可审计的重置并生成新的随机初始密码。不得通过公网首注、代码、镜像或普通配置创建或重置该管理员。
- Default Platform Admin 的受限重置由 IAM 同一制品的独立 bootstrap reset 操作执行，只从外部挂载文件读取新随机密码与 UUIDv7 `resetRequestId`。仅当该 Identity 尚无有效普通 Password Credential 时允许；它在 IAM 单一事务中永久失效全部旧 Initial Platform Credential、撤销对应的全部 `INITIAL_PASSWORD_CHANGE` Family，并创建新的 24 小时初始凭据。同一 `resetRequestId` 幂等重放，新重置必须使用新 ID；已有有效普通 Password Credential 时操作失败并要求走正式账号恢复流程。该入口不提供 HTTP API、不返回或记录明文密码，也不能复用首次创建逻辑。
- 无风险事件下不强制周期性改密；发生泄露、高风险登录或管理员强制重置时，要求改密并撤销相关会话。
- Tenant 管理员邀请成员只发送一次性、限时链接；新 Identity 先自行完成 Password Setup 并登录，再显式接受邀请；已有凭据须复用，不预设角色，不自动切换 Tenant Context，见 [ADR 0059](adr/0059-invitation-acceptance-follows-password-setup-and-login.md)。管理员不能设置或查看密码。
- Password Recovery 使用独立、30 分钟有效且同 Identity 仅最新一份有效的一次性邮箱凭据；请求入口统一反馈，成功后撤销该 Identity 全部会话与有效 Access Token，不自动登录、不修改 Membership 或角色。具体规则见[核心领域契约](17-core-domain-contracts.md#password-recovery)，当前不表示恢复 API 已实现。
- 初始 Tenant 管理员的 Identity 从未拥有任何 Credential 时，IAM 使用 CSPRNG 生成 256 位 Password Setup Challenge Token，仅持久化其 SHA-256 摘要；Challenge 自创建起有效 24 小时，每个 Identity 同时最多一个有效 Challenge，重发时原子作废旧 Challenge。`DeliverPasswordSetup` 在 SMTP 明确接受后才记录稳定成功；同一 `requestId` 的未完成重试必须原子作废旧 Challenge、生成新 Token 并发送新邮件，SMTP 超时或进程崩溃后可能迟到的旧邮件只能携带已失效 Token。IAM 不得为重发而持久化可恢复的明文或加密 Token。兑换必须携带 UUIDv7 `Idempotency-Key`，并在同一事务中创建 Password Credential、消费 Challenge 和记录稳定 `204` 结果；相同 Token 与相同 Key 可重放该结果，其他 Key 不得复用已消费 Token。已有有效 Password Credential 的 Identity 直接复用且不发送 Setup；存在有效或过期 Initial Platform Credential，或存在已失效 Password Credential 时，管理员初始化必须在 Quota 扣减前返回 `409 / IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`，不得通过 Setup 绕过既有恢复流程。因先前失败工作流创建但始终没有 Credential 的 Identity 仍可安全重试 Setup。无效、过期、已使用或已被替换的 Token 统一返回 `400 / PASSWORD_SETUP_TOKEN_INVALID`，不得暴露具体状态；幂等记录不得保存或快速哈希新密码。
- Password Setup 邮件链接固定使用 `https://console.<root>/password-setup#token=<token>`，Token 是 32 字节随机值的 43 字符无填充 Base64URL，只能位于 URL Fragment，不得进入查询参数。当前切片交付完成该安全闭环所需的最小页面：页面读取后必须立即从地址栏移除 Fragment，仅在内存保存 Token，设置 `Referrer-Policy: no-referrer` 且不加载第三方资源，最终通过 JSON Body 向 IAM 提交 Token 与新密码；其他控制台功能仍由控制台阶段交付。
- 服务间采用 OAuth 2.0 Client Credentials。服务 Token 的身份与授权语义只限 `client_id` 与显式 `scope`，不得伪造用户、Membership、Tenant 或用户 RBAC 上下文；缺少所需 scope 时必须以 `403` 拒绝。服务可在契约明确的内部调用或可信消息元数据中携带 Tenant Operation Target，但下游必须按 `client_id` 与 `scope` 授权并校验其与目标资源的关系，它不建立 Tenant Context。OAuth Client 的 `allowedScopes` 使用受限 `text[]` 存储，MVP 允许 `runtime:read`、`runtime:quota:write`、`tenant-access:membership:read`、`tenant-access:tenant:read`、`iam:identity:write`、`iam:password-setup:write`、`iam:platform-role:read`、`iam:sessions:write` 与 `entitlement:quota:write`。前三个保留服务使用固定精确集合，普通 Runtime Client 只能获得两个 Runtime Scope。Client Secret 由 CSPRNG 生成 256 位随机值，仅在创建、轮换或一次受限签发恢复时明文展示一次；平台仅保存其 SHA-256 摘要。常规轮换时新 Secret 立即可用，旧 Secret 固定重叠 24 小时，窗口内拒绝再次轮换；疑似泄露必须吊销整个 Client。Client 吊销通过 PostgreSQL 权威事实与无 TTL Redis `client_id` 拒绝索引立即终止新签发和全部既有 Service Access Token，签发方与验证方对 Redis/Ready 不可用失败关闭。完整管理规则见 [Client Credentials 管理规格](22-oauth-client-credentials-management.md)。
- `iam-service`、`tenant-access-service` 与 `entitlement-service` 的保留 OAuth Client 由 IAM 同一制品的独立显式 bootstrap 操作创建。部署侧为每个服务生成固定 UUIDv7 `client_id` 和 256 位随机 Secret；Flyway 完成后的一次性 Job 从外部挂载 Secret 文件读取三组 ID、Secret 与固定 Scope，并在 IAM 单一事务写入。每个运行服务只挂载自己的 ID 与 Secret；正常 IAM 启动、Flyway、源码、镜像、Compose 默认值和 Nacos 普通配置均不得创建或保存这些 Secret。Job 重跑时只有 ID、Secret 摘要与 Scope 完全一致才幂等成功，任何差异都失败并转人工处理；后续轮换走正式 Client 管理生命周期。见 [ADR 0030](adr/0030-deployment-bootstraps-reserved-service-oauth-clients.md)。
- IAM 经版本化 JWKS 发布公钥。生产环境的 JWT 私钥使用 KMS/HSM 托管的不可导出 `RS256` Signing Key；IAM 仅以工作负载身份调用签名接口，每个 KMS 密钥版本映射唯一 `kid`，不得将私钥挂载到应用进程。Gateway、SDK 和业务服务的验签算法白名单只能包含 `RS256`。
- Signing Key Metadata 必须持久化唯一 `kid`、KMS/HSM Key Version 引用、JWKS 所需的公开 `n`/`e`、`maxIssuedTokenTtl` 与生命周期时间，私钥绝不入库。其状态只能为 `PUBLISHED`、`ACTIVE`、`RETIRING`、`RETIRED` 或不可逆的 `REVOKED`；全局恰有一个 `ACTIVE` Key。JWT Signing Key 的常规轮换由生产部署的合规策略触发，不在代码中写死周期：新 `kid` 先以 `PUBLISHED` 与旧公钥共同发布于 JWKS，等待 5 分钟缓存窗口后才转为 `ACTIVE`；TTL 配置增大时必须先原子提高 Active Key 的 `maxIssuedTokenTtl` 才能按新值签发，配置降低不回减。原 Active Key 转为 `RETIRING` 后，公钥至少保留到 `retiringAt + max(30 分钟, maxIssuedTokenTtl + 30 秒)`，随后才可转为 `RETIRED` 并移出 JWKS。疑似私钥泄露时，立即转为 `REVOKED`，停止签名、将其 `kid` 写入 Redis 撤销集合并从 JWKS 移除；Gateway、SDK 和业务服务每个请求都检查该集合，Redis 不可用时 fail-closed，即使本地 JWKS 缓存仍有该公钥也必须拒绝。开发环境采用相同 `kid`/JWKS 切换语义，但仅允许显式本地轮换。
- 仅开发 profile 可使用显式本地初始化生成的开发专用非对称密钥对；密钥位于 `.gitignore` 的本地密钥目录，并以只读方式提供给 IAM。Compose 不得自动生成、删除或在生产 profile 回退使用该密钥；开发密钥轮换必须显式触发。
- IAM 启动时必须确认数据库中恰有一个元数据完整且合法的 `ACTIVE` Key，否则拒绝启动；启动检查不调用 KMS 试签。运行中 KMS 签名失败时，登录或刷新返回 `503 / TOKEN_SIGNING_UNAVAILABLE`，且签名成功前不得创建登录 Family、消费或轮换现有 Refresh Token。KMS 故障不影响 IAM 从持久化公开材料发布 JWKS；Signing 健康状态单独暴露并告警，不自动降级到本地私钥或其他非 Active Key。

## 授权、租户与数据隔离

用户请求必须经 Authentication → Identity → Membership → Tenant Resolve → Tenant Context，再执行 Permission、Feature、Quota 和业务逻辑。Tenant Context 需可信、明确、支持跨线程、异步和消息场景传播；不得使用请求中的 Tenant 标识作为安全边界。服务请求不建立用户 Tenant Context，且只能按 `client_id` 与显式 `scope` 执行授权。

Platform 管理 API 必须先验证 Platform 形态的 User Access Token，再由承载该 API 的服务使用 `iam:platform-role:read` 同步向 IAM 复核 `identityId` 当前持有操作要求的 Platform Role。Platform Role 不写入 User Access Token，也不得由 Gateway Header 或 Token 缺少 Tenant Claim 推定；IAM 不可用时平台管理操作 fail-closed。

RBAC 以 `Membership → Role → Permission` 实施。平台角色与租户角色隔离；Permission 与 Feature 必须可同时校验。所有 Tenant 范围数据表采用 `tenant_id` 与 PostgreSQL RLS；事务级 `app.tenant_id` 缺失时默认拒绝，常规账号无 `BYPASSRLS`。

## 网络、密钥与基础设施

- API Gateway 是唯一公网入口；领域服务不直接暴露公网。
- 生产环境所有通信采用 TLS；服务间 gRPC 使用 mTLS。
- PostgreSQL、Redis、Kafka 与对象存储使用加密连接和各自凭据认证。
- JWT 私钥、数据库密码、Client Secret、Kafka 凭据等由外部密钥管理服务权威托管。生产 JWT 私钥只驻留 KMS/HSM；Kubernetes 中 IAM 以受限工作负载身份调用签名接口，虚拟机仅以受限系统凭据文件取得该调用权限。其他密钥可通过受控同步挂载或受限系统凭据文件注入。密钥禁止进入源码、镜像或普通配置文件。
- IAM 通过最小 `JwtSigningPort` 请求签名，输入不透明 `kmsKeyVersionRef`、固定 `RS256` 与 JWS Signing Input，输出签名字节；领域模型不包含厂商字段。`dev`/`test` 仅提供只读本地 PEM 的 JCA 实现和内存测试 Fake。本仓库在生产部署目标确定前不引入厂商 KMS SDK 或自创通用 KMS HTTP 协议；`prod` 缺少外部适配器 Bean 时拒绝启动，绝不回退本地私钥。当前 Compose 验收只覆盖开发签名，实际生产目标须以独立适配器接入对应 KMS/HSM。
- Redis 作为 Token `jti` 黑名单、Signing Key `kid` 撤销集合和会话等安全依赖，生产环境必须高可用并具备自动故障转移。

## API、前端与运行时防护

- Gateway 以 Redis 令牌桶按 IP、Identity、Client、Tenant 限流；策略按环境配置。
- CORS 默认拒绝。API 的凭据型白名单仅包含两类 Console；业务 Remote 仅允许 Tenant Console Shell 无凭据加载静态资源，具体规则以本节的 `browser.rootDomain` 推导配置为准。
- Platform Console 与 Tenant Console Shell 分别只在自身受控 Origin 的共享认证 Runtime 内保存对应 Browser Session Slot 的 Access Token；业务 Remote 只能经 Shell 的认证能力和共享类型化 HTTP Client 调用正式 operation，不能读取或保存令牌，也不能创建第二个 Runtime。
- Module Federation Remote 的来源和版本由 Manifest 白名单控制；Tenant 管理员不能录入任意远程脚本地址。
- 所有创建和变更 API 使用幂等键；失败以 `application/problem+json` 返回稳定 `code` 与 `traceId`。

## 数据、日志与审计

平台数据分为公开、内部、机密、敏感个人信息四级；业务模块注册其数据分类并映射到该分级。日志、Trace、审计和 Kafka 事件默认不得包含密码、Access / Refresh Token、Client Secret、完整证件或其他原始敏感个人信息，统一通过字段白名单和脱敏处理。

IAM 首个认证切片同步落地服务自有 Transactional Outbox，并可靠发布 `com.saas.forge.iam.session.started.v1`、`com.saas.forge.iam.session.revoked.v1`、`com.saas.forge.iam.refresh-replay-detected.v1` 与 `com.saas.forge.iam.password.changed.v1`；现有批量 `com.saas.forge.iam.sessions-revoked.v1` 仍只用于成员禁用、Tenant 冻结等跨服务撤销。事件与对应数据库事实在同一事务提交，且只含内部 ID、Purpose、上下文类型、结果、时间和 `traceId`。正常刷新成功只记录指标；密码错误、未知邮箱和锁定拒绝只写白名单结构化安全日志与指标，不伪称可靠 Outbox 事实。事件、日志均不得包含邮箱、密码、JWT、Refresh Token、Cookie、IP 原文或完整 User-Agent。

Audit 服务只追加审计记录，平台或租户管理员不得修改或物理删除。审计保留期由平台级合规配置确定。导出保留任务元数据与审计记录；临时导出文件通过短期签名 URL 获取，并按可配置留存期从对象存储物理删除。

首个事实消费切片的 Audit Record 只保存规范化列和事件类型白名单元数据，不复制完整事件 Envelope。`audit_records` 不启用 Tenant RLS：Platform 审计人员需要跨 Tenant 调查，且系统级事实允许 `tenant_id` 为空；隔离区原始载荷仅在契约与安全校验通过后保存，非法或疑似敏感载荷只保存摘要及诊断元数据。见 [ADR 0035](adr/0035-audit-records-do-not-use-tenant-rls.md)与 [Audit 成功事实消费设计](24-audit-success-fact-consumption.md)。

## 安全验证与应急

- CI 执行依赖、镜像漏洞扫描和 Gateway 的 OWASP ZAP 基线扫描；高危和严重漏洞阻断合并与发布。
- RLS 为强制集成测试门禁：应用数据库角色必须无法跨 Tenant 读写改删，缺失 Tenant 上下文默认拒绝。
- 安全事件须关联 `traceId`、影响范围、开始/恢复时间、根因和改进项；变更、漏洞处理、恢复演练和应急响应需形成可审计记录。
