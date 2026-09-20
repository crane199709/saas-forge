# 统一认证协议与兼容迁移方案

> 状态：协议设计已确认，2026-09-15 用户完成 Q1–Q10 及完整方案确认。未修改正式 OpenAPI、运行代码或历史迁移；本票只完成协议准备，不代表业务实现或业务验收完成。
>
> 来源：[父 PRD #201](https://github.com/crane199709/saas-forge/issues/201)、[Issue #202](https://github.com/crane199709/saas-forge/issues/202)。2026-09-15 用户确认 Q1–Q10；其中 Q6 明确使用平台管理、公司工作台两组卡片，公司组直接展示所有有权限的公司，Q7 明确“旧登录直接停止”。

## 已确认的用户可见行为

1. **同一浏览器的统一 Console 共用当前工作上下文。** 在一个标签页切换平台管理或 Tenant 工作区域，其他标签页停止旧上下文操作、清除旧数据并同步新上下文。不能同时在不同标签页维持不同 Tenant 工作上下文。
2. **无可用工作上下文时保留受限会话。** 密码认证成功但没有 Platform Role 或 Accessible Membership 时，显示“暂无可访问的工作空间”；仅允许重新检查可用上下文和退出，不签发业务 User Access Token。权限补齐后可重新检查并进入。该状态与 Initial Credential Session 分开定义，不能绕过首次改密。
3. **当前工作上下文权限失效时结束当前浏览器会话。** 例如当前 Tenant 被冻结，即使该 Identity 仍有 Platform Role，也必须终止当前会话并同步全部标签页退出；重新登录后读取剩余权限，不自动转入另一个工作上下文。本决定不要求终止该 Identity 在其他设备上的全部会话。
4. **用户主动切换前保护跨标签页未保存内容。** 检测到任何标签页有未保存内容时先阻止切换，允许取消并返回保存，或明确选择“放弃未保存内容并切换”。系统不自动提交表单。该保护不阻止权限失效、会话撤销等安全退出。
5. **首次进入新版不继承旧双 Console 登录。** 结束当前浏览器的旧 Platform 与 Tenant 两份会话，再要求重新输入密码；不猜测或合并两份会话的 Identity，不因此退出其他设备。不能只删除 Cookie 而保留可用的旧会话。
6. **多个可用工作上下文在同一页以分组卡片选择。** “平台管理”组展示有权进入的平台入口；“公司工作台”组直接展示所有 Accessible Membership 对应公司卡片，点击公司卡片直接进入该 Tenant 工作上下文，不另设先选择公司工作台、再选择公司的两步流程。无权入口不展示。每次重新输入密码登录后按权威结果选择，只有一个可用工作上下文时按父 PRD 直接进入；刷新或重开标签页恢复当前仍有效的工作上下文，不重复要求选择。
7. **新版正式启用时直接停止旧登录。** 不保留新旧登录并行使用；旧入口引导到新版，旧会话不能恢复，也不能通过旧页面重新建立登录，用户须在新版重新登录。这是已确认的迁移设计，不表示本轮已经停用入口或获得执行发布的指令；停止旧协议的具体影响、发布顺序及回退条件仍须补齐，不借此修改历史契约基线或扩大一次性 v1 豁免。
8. **无法确认其他标签页状态时，显式确认后才切换。** 后台标签页休眠或无响应时，提示无法确认未保存内容，允许取消，或明确选择仍然切换并放弃其他页面的未保存内容。旧页面恢复后必须清除旧内容并同步当前工作上下文，不能继续提交旧 Tenant 的表单。
9. **故障回退不自动恢复旧登录。** 优先退回仍支持统一登录的可用版本；没有兼容可用版本时显示维护页面并修复。确需重新开放旧登录时，单独确认影响后再执行；不复活旧会话。
10. **退出结果未知时禁止换账号。** 立即清除所有标签页私有内容，显示“退出尚未完成”，保留非敏感退出待完成标记。重试至服务器确认原会话结束后才能登录新 Identity；重载页面不能自动恢复原会话。

## 1. 范围、状态与事实依据

本票交付实施协议，不新增可调用 API，不发布 Client，不执行迁移或停用旧登录。前端承接于用户已有的 saas-forge-web，复用完整 Soybean 底座提交 `7613bd206cd42001b40e3eafceeb895dcbc277a8`；一个应用、一个受控 Console Origin、一个登录页。后端只验收后端；前端连接已经启动的后端验证页面，不启动或接管后端进程。

下文 operation、Cookie、状态和错误码均为 **拟实施的 v2 设计**，不能从当前 v1 Client 调用。产品决定与实现设计均已获用户确认；新 ADR 已接受，正式制品与业务实现仍由后续任务交付。

核实基于后端提交 `4bf75fa09010d577cf8c44752972c38383ffd38b`：

- 现行 [OpenAPI v1](../saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml) 登录使用 `contextType`，刷新与退出使用 `sessionSlot`；现行协议没有本方案所需的统一登录状态。
- [ADR 0038](adr/0038-browser-sessions-use-intent-bound-slots.md) 的两个 Cookie 与独立退出、[ADR 0027](adr/0027-login-explicitly-selects-platform-or-tenant-context.md) 的登录前意图选择需要与新目标区分；历史兼容基线及已执行迁移保持不可变，不扩大已有一次性豁免。
- 现有 v1 消费者仍可运行，尚无证据证明不存在外部消费者。新版启用时停止旧登录的决定不代表这些消费者已经迁移；发布影响必须明确记录，本轮不执行停用。
- 现行普通与 Tenant 待选择 Family 为绝对 8 小时、闲置 30 分钟；首次改密为登录后 10 分钟与初始凭据到期时间的较早者。首次改密成功返回 204、撤销受限 Family，不签发业务 Token。依据 IAM 的 `RefreshTokenFamily`、`InitialPasswordChangeService` 与 `AuthenticationController`。
- 现行 `LogoutTransaction` 只撤销 Cookie 对应 Family 和请求携带的一个 Access Token `jti`；失败响应可能清 Cookie。它尚不满足跨标签全部 Token 撤销和退出可重试要求，不能直接复用为 v2 完成证据。
- `HttpRouteCatalogGenerator` 目前只读取正式 v1 契约，security scheme 映射是固定的；新增 v2 需要同步路由目录生成、Gateway 与生成 Client。`V1ContractCompatibilityTest` 未比较全部 security 语义，通过它不能单独证明认证兼容。

## 2. 会话模型与授权边界

### 2.1 单一会话与当前工作上下文

IAM 为统一 Console 的 Browser Session Slot 维护一个当前 Family 指针及单调 `revision`。一个 Slot 同时最多关联一个未结束的 Unified Console Session；一个 Session 的 Identity 从创建到结束不变。登录另一 Identity 必须先结束当前 Session，不能在原 Family 上改 Identity。

普通会话使用一个 Family，工作上下文可以是未选择、Platform，或一个已验证的 Membership/Tenant 对。平台与公司之间切换不创建第二个 Family，也不保留可独立恢复的旧管理视图。Initial Credential Session 是独立的受限类型，成功改密后结束，不能通过上下文选择升级成普通会话。

| 公开 `state` | 登录或恢复的权威条件 | `activeContext` / Token | 允许的用户行为 |
|---|---|---|---|
| `ANONYMOUS` | 无有效 Session；Runtime 根据 bootstrap 无会话或明确结束结果归一化 | 无 / 无 | 输入密码登录、Password Setup |
| `PASSWORD_CHANGE_REQUIRED` | Initial Platform Credential 验证成功 | null / 无 | 首次改密、退出 |
| `NO_AVAILABLE_CONTEXT` | 常规密码已验证，Platform Role 与 Accessible Membership 均不存在 | null / 无 | 重新检查、退出 |
| `CONTEXT_SELECTION_REQUIRED` | 常规密码已验证且有候选，尚未建立当前工作上下文 | null / 无 | 卡片选择、重新检查、退出 |
| `AUTHENTICATED` | 当前 Platform Role 或当前 Membership/Tenant 在本次核验中有效 | Platform 或 Tenant / 登录与刷新可发 Token | 对应权限的业务操作、选择其他上下文、退出 |

无权限等待与待选择只是普通会话的受限阶段，不是 Platform 授权；不得发“无 Tenant 的用户 Token”代替受限状态，因为现行消费者可能把它当作平台身份。密码认证一次成功后查询两类权威权限，不调用两次旧登录，也不把下游超时记作密码错误。

登录时：Initial Credential 优先；普通候选为零进入等待页，为一自动建立该工作上下文，多于一进入分组卡片页。一个平台入口加一个公司入口也属于多个候选。后续只读核验不会静默改变既有当前上下文；等待页重新检查后若只剩一个候选，由 Runtime 发正式选择请求，不能靠读取响应自行获得权限。

### 2.2 权限权威与失效

- IAM 复核 Platform Role；Tenant Access 复核 Accessible Membership、Tenant Access Status 和品牌。Platform Role 不自动授予任何 Membership，选择请求只提交目标 Membership ID，不允许提交 tenantId 覆盖服务端解析结果。
- 列表只返回该 Identity 有权使用的完整候选；不能用截断前 100 项冒充“全部公司”。超过现行查询能力时返回 `503 CONTEXTS_UNAVAILABLE`，不能猜测单一候选或展示不完整的选择结果。实施需扩展内部查询以完整取得候选；若采用分页，读完全部页并确认同一快照后才能据此自动进入唯一候选。
- 每次选择、切换、刷新、权威会话读取均重新核验当前上下文；当前上下文已失效时结束整个当前 Family 和其全部未过期 Token，不降级回选择页。目标上下文失效但当前仍有效时，只拒绝目标；权威依赖不可用时返回 503，不把未知当作无权限或允许。
- 后端每次业务操作仍复核必要权限、Fence、Tenant 状态与原操作者；菜单和卡片仅控制显示。未选中的另一个权限被撤销不会单独终止当前有效上下文，但它必须从最新候选中消失。
- Tenant 冻结、Membership 禁用、Platform Role 失效及 Identity/凭据安全事件必须覆盖 v2 Family。批量撤销沿用现有目标 Fence 与分批恢复，不能遗漏新会话模型。解除冻结不复活已撤销 Family。

## 3. Cookie、Token 与并发定位

### 3.1 两枚 Cookie，一个登录

拟新增两枚 API host-only Cookie，均为 `Secure; HttpOnly; SameSite=Strict; Path=/`、无 Domain：

| Cookie | 用途 | 不允许承担的能力 |
|---|---|---|
| `__Host-sf_console_slot` | 不可猜测的浏览器 Slot 定位值，服务端只存摘要；串行化登录、定位待完成退出 | 单凭它不得读 Identity、候选、业务数据，不能刷新或签 Token |
| `__Host-sf_console_refresh` | 当前 Family 的轮换 Refresh Token，服务端只存摘要 | 不能选择另一 Slot、Identity 或绕过权限核验 |

定位 Cookie 不是第二份登录。保留它是为了在并发登录、刷新 Cookie 丢失或退出失败后仍能定位同一个浏览器会话，避免通过新建 Family 绕过未结束的旧登录。它仅额外允许受 CSRF 保护、携带准确 revision 的结束会话请求；这个能力不授予访问权限。

首次 bootstrap 在同 Origin Web Lock 内创建 Slot；已有 Slot 时只读取非敏感 revision/是否存在会话/转换阶段，不返回 Identity。持锁到响应完成后其他标签页才 bootstrap。Slot Cookie 不在普通轮换中更换值，寿命覆盖关联 Family 的剩余绝对期限；已结束会话的重试记录保留至相关旧 Token 均过期且超过允许的恢复窗口。定位值缺失但仍有 Refresh Cookie 时，先验证并结束对应旧 Family，再创建空 Slot；不能凭任意提交的 Slot ID 绑定旧会话。

Refresh Cookie 保留现行闲置期限与绝对期限的较小 Max-Age。两个 Cookie 的写入均由服务端与浏览器完成，不能暴露成页面/Remote 的业务调用参数。

### 3.2 版本与请求竞争

- `sessionId` 为公开、不具授权能力的 UUIDv7；`revision` 是 Slot 单调版本，以十进制字符串传输，避免 JavaScript 大整数精度丢失。登录、上下文提交、进入退出流程、结束等状态变化推进 revision；普通 Token 轮换不推进它。
- Family 的 `contextVersion` 保留现有单调语义。Access Token 关联 `sessionId`、签发时上下文版本及 `jti`；它仍只表示一个工作上下文，不是平台/租户通用权限包。
- 控制 Session 的新请求由 Runtime 自动携带 `If-Match: "<revision>"`。除 bootstrap、只读查询及 Password Setup 外，缺少前置版本返回 428，版本过时返回 412；在密码校验、消费 Token 或变更会话之前拒绝。业务表单不负责设置这些头。
- 持久操作重试先按同 Slot 的原 Idempotency-Key 找到记录并校验原目标/指纹，再恢复或重放；只有匹配的既有操作不受随后推进的 revision 阻挡。新键仍需最新 If-Match，不能将旧操作重试重解释为当前 Session 的新命令。Refresh 的一次替代恢复另按原轮换键和原上下文版本核验，不能跨已发生的上下文切换恢复旧 Token。
- bootstrap、会话读取、登录、刷新及上下文提交/退出/首次改密响应返回 `ETag: "<revision>"`；204 同样返回 ETag。CORS 显式暴露 ETag，由共享 Client 读取。已提交操作重放携带原结果版本；若其后又有变更，下一命令按412重新同步，不擅自使用旧版本。
- 数据库 Slot/Family 锁与条件更新是最终串行化边界。第二个并发登录即使来自不同标签页也不得创建第二个当前 Family。刷新准备的 Token 在提交前必须复核 Family 状态、上下文版本、Slot 当前指针和退出标记。
- 同一 Slot 的旧退出重试按原请求键找到原 Session；不能把 Cookie 此刻指向的新 Identity 当作原退出对象。退出键绑定 Session 与请求指纹，重放原结果不影响后来登录。

### 3.3 晚到的 Set-Cookie

忽略旧 JSON 或取消 Fetch 不能保证浏览器忽略 Set-Cookie；并行 Cookie 响应存在竞争，见 [RFC 6265 §4.1.1](https://www.rfc-editor.org/rfc/rfc6265#section-4.1.1)。因此不能仅靠前端代次声称已经解决。

服务器始终要求 Refresh Token 所属 Family 等于 Slot 当前 Family，且仍有效。晚到的旧 Cookie 即使覆盖了新 Cookie，也不能恢复旧 Identity。发现 Slot/Refresh 不匹配时返回 `409 SESSION_COOKIE_MISMATCH`，不签 Token、不选择任何一方为当前用户、不修改 Cookie；Runtime 隔离所有私有内容，提示结束当前会话后重新登录。定位 Cookie与准确 revision 使这次退出仍可完成，不以保存明文 Refresh Token 来恢复。

服务器已识别为过时的请求、未知错误和退出失败响应不得设置或清理 Cookie；已完成操作的同键重放也不重复清 Cookie。首次确认对应 Session 结束的成功响应可以清 Refresh Cookie，但提交后才迟到的响应无法收回：即使清掉新 Cookie，也至多导致上述重新登录，不能恢复旧身份或放行旧操作。新登录前先等待本 Runtime 的在途认证请求结束；休眠/失联页面恢复后仍按服务器状态核验，不能假定取消请求等于服务器没有提交。

这是可用性边界：极端响应乱序允许要求重新登录，不承诺无感恢复；授权和私有数据隔离必须保持。

## 4. 拟实施的公开接口

认证使用显式 `/api/v2/auth/`；既有业务 operation 可继续使用 v1 路径，但必须接受并验证新的单上下文 Token。所有接口进入正式 OpenAPI、Gateway route catalog 及同一固定版本 Client 后才可调用，不允许前端手写临时 fetch 或尝试 v1 兜底登录。

除表中例外，Cookie 请求使用定位 Cookie + 当前 Refresh Cookie；所有读取与结果均 `Cache-Control: no-store`。Token 仅存在于 login/refresh 成功响应，服务端不保存或原样重放这些成功 body。

| 方法与路径 | 拟定 operationId / 输入 | 成功结果与副作用 |
|---|---|---|
| `POST /api/v2/auth/bootstrap` | `bootstrapConsoleSession`，`{}`；无需已有登录 | 200 `{revision, sessionPresent, transition}`；只在缺少定位 Cookie 时初始化 Slot，不创建认证会话 |
| `POST /api/v2/auth/login` | `loginConsoleSession`，`{email,password}`，If-Match | 200 AuthenticationResult；仅一次凭据认证，创建一个 Family；活动会话存在时先返回 409，不验证第二个账号密码 |
| `GET /api/v2/auth/session` | `getConsoleSession`，无业务参数 | 200 SessionSnapshot，不轮换 Cookie、不延长会话；有效 Session 必需，权限失效时执行安全撤销并返回 403 |
| `GET /api/v2/auth/contexts` | `getAvailableWorkContexts`，无业务参数 | 200 `{sessionId,revision,availableContexts}`；完整权威候选，受限首次改密拒绝 |
| `POST /api/v2/auth/context-selections` | `selectConsoleContext`，目标联合类型，If-Match、Idempotency-Key | 204，首次选择或平台/公司/Tenant 间切换；提交后必须 refresh 获取 Token，不直接返回 Token |
| `POST /api/v2/auth/refresh` | `refreshConsoleSession`，`{}`，If-Match、Idempotency-Key | 200 AuthenticationResult；按当前状态轮换，一次刷新不得改到另一个工作上下文 |
| `POST /api/v2/auth/logout` | `logoutConsoleSession`，`{}`，If-Match、Idempotency-Key；定位 Cookie 足以执行受限撤销 | 204，当前 Family 与全部未过期 JTI 撤销已交付后才成功，清 Refresh Cookie；可重试，不终止其他 Slot |
| `POST /api/v2/auth/password-changes` | `changeConsoleInitialPassword`，`{newPassword}`，If-Match、Idempotency-Key | 204，消费初始凭据、结束受限会话、清 Refresh Cookie，重新登录 |
| `POST /api/v2/auth/password-setups` | `establishConsolePassword`，`{token,newPassword}`，不依赖已有会话、无 If-Match | 204；沿用 Password Setup Challenge 一次消费，不自动登录、不替换已有凭据 |

Idempotency-Key 使用现行 UUIDv7 约束。上下文选择、退出、首次改密持久保存非敏感结果/状态及指纹；同键改请求为 409。没有 Token 的 204 可以稳定重放。login 无通用幂等响应：结果未知先 bootstrap 判断是否已有 Session；若 Cookie 已收到则 refresh，否则结束已建立但无法恢复的会话后重新登录，不能自动重复提交密码。

首次改密的非敏感结果记录引用本次建立的 Credential ID；同键重放使用该 Credential 既有的 Argon2id 哈希核对 NFC 密码，不另存密码、快速摘要或 Token。凭据替换、原受限 Family 撤销、Slot 清空并递增 revision、Outbox 和结果记录在同一事务提交。原键重放不清除后来建立的 Refresh Cookie；失败输入不消费受限会话。前端只保留非敏感操作键及原 Session/revision，未知结果先 bootstrap 核查，不持久化或自动重发新密码。


bootstrap 的 `transition` 为 `NONE | SWITCH_PENDING | CONTEXT_REFRESH_REQUIRED | ENDING`，只用于恢复协调，不携带 Identity。`SWITCH_PENDING` 时 session/contexts/refresh/新选择返回503 `SESSION_TRANSITION_PENDING`，原切换键可恢复，退出可以终止该流程；`CONTEXT_REFRESH_REQUIRED` 时读取和新选择返回409，允许refresh及退出；`ENDING` 时读取、refresh、选择及新login都返回503，只有退出恢复可以继续。不得返回可操作的 AUTHENTICATED Snapshot 让其他标签页绕过未完成转换。每个 Session 最多一个退出根流程；本地原键丢失时，在同 Slot、最新 revision 下提交的新退出键绑定既有结束流程，不新建或改变退出目标。

密码类请求不持久化明文密码、Challenge 或可用于离线猜测密码的无密钥指纹；需要判定同键不同密码时使用受保护服务端密钥计算的指纹，密钥由既有外部凭据机制注入，不写入配置中心。初始会话调用 refresh 返回403 `INITIAL_CREDENTIAL_RESTRICTED`；页面恢复通过只读 Session Snapshot，不能轮换延长受限会话。

### 4.1 请求与结果形状

上下文选择目标严格二选一，不接受角色、Identity 或 Tenant ID 注入：

```json
{"type":"PLATFORM"}
```

```json
{"type":"TENANT","membershipId":"01994500-0000-7000-8000-000000000001"}
```

SessionSnapshot 的普通会话字段：`sessionId`、`revision`、`state`、`identity: {identityId,email,displayName?}`、`activeContext`、`availableContexts: {platform,companies}`。`platform` 为是否存在平台入口的布尔值；`companies` 是完整 `{membershipId,tenantId,tenantDisplayName}` 数组。租户 activeContext 还包含当前权威品牌 Profile；无选中项时为 null。Initial Credential 结果只含 `sessionId/revision/state`，不包含候选、角色或 Token。

AuthenticationResult 为判别联合：`AUTHENTICATED` 分支为 SessionSnapshot 加 `accessToken`、`tokenType: Bearer`、`expiresIn`；其余分支仅返回对应 Snapshot。正式 schema 必须用 required/oneOf/additionalProperties 明确互斥字段，禁止受限分支夹带 Token。示例为无可用上下文的公开结果：

```json
{
  "sessionId": "01994500-0000-7000-8000-000000000002",
  "revision": "12",
  "state": "NO_AVAILABLE_CONTEXT",
  "identity": {
    "identityId": "01994500-0000-7000-8000-000000000003",
    "email": "member@example.invalid"
  },
  "activeContext": null,
  "availableContexts": {"platform": false, "companies": []}
}
```

GET Snapshot 永远没有 Access Token；`AUTHENTICATED` 只说明当前上下文经核验有效，不授权页面自己合成 Token。普通等待/选择 Session 的 state 由最新候选是否为空表达，但只读候选变化不改变已建立的工作上下文；选择提交仍按最新权威结果验证。

### 4.2 错误契约

错误为现行 Problem Details 形状，使用稳定 `code`、HTTP status 和脱敏 traceId；不暴露邮箱是否存在、具体安全校验失败项、他人 Membership 或 Token。以下为 v2 错误全集的认证部分，字段校验沿用正式通用错误。

| HTTP / code | 前端动作 | 会话与 Cookie 后果 |
|---|---|---|
| 400 `VALIDATION_FAILED` | 修正输入；不作为密码错误自动重试 | 不变 |
| 401 `AUTHENTICATION_FAILED` | 统一凭据错误提示，包括账号不存在/锁定 | 无新会话；只记真正凭据失败 |
| 401 `SESSION_INVALID` | 清内存、回登录或执行必要的结束恢复 | 不恢复旧 Family；不在未知错误响应清 Cookie |
| 403 `BROWSER_REQUEST_REJECTED` | 来源/环境错误提示，不报密码错误 | 不变，不泄露具体失败项 |
| 403 `INITIAL_CREDENTIAL_RESTRICTED` | 仅显示首次改密 | 保留受限 Session，不签 Token |
| 403 `TARGET_CONTEXT_UNAVAILABLE` | 刷新候选，解释目标不可访问 | 当前合法上下文保留；前端重新核验后才能恢复显示 |
| 403 `CURRENT_CONTEXT_REVOKED` | 所有标签页退出、重新登录 | 当前 Family 全部 JTI 撤销；撤销无法确认时改报 503，保持阻断 |
| 409 `SESSION_ALREADY_ACTIVE` | 先恢复或明确退出再换账号 | 不验证新凭据，不创建第二个 Family |
| 409 `SESSION_COOKIE_MISMATCH` | 隔离私有内容，结束当前 Session 后重登 | 不自动选择 Cookie 中任一 Identity，不改 Cookie |
| 409 `REFRESH_ROTATION_IN_PROGRESS` | 有界等待 Retry-After，再按原轮换流程协调 | 不撤销，不改 Cookie |
| 409 `REFRESH_CONTEXT_CHANGED` | 丢弃旧准备结果，重新读取权威状态 | 不消费旧 Token，不改 Cookie |
| 409 `CONTEXT_REFRESH_REQUIRED` | 只允许 refresh/退出 | 切换已提交，不可恢复旧 Token |
| 409 `IDEMPOTENCY_KEY_REUSED` | 停止该请求，保留原操作 | 不改变原操作目标 |
| 412 `SESSION_REVISION_CHANGED` | 隔离旧页面，重新同步；业务变更不得自动重放 | 不执行原操作，不改 Cookie |
| 428 `SESSION_REVISION_REQUIRED` | Client 版本/调用契约错误 | 不执行原操作 |
| 503 `CONTEXTS_UNAVAILABLE` | 暂停依赖上下文的操作，允许重新核验 | 不把未知变成空权限，不建立新上下文 |
| 503 `SESSION_TRANSITION_PENDING` | 保留同键并重试，不创建新切换/退出 | 持久流程尚未确认，保持阻断，不改 Cookie |
| 503 `SESSION_SECURITY_UNAVAILABLE` | 显示暂不可用，按 Retry-After 手动恢复 | Redis、撤销或状态存储未知时失败关闭，不改 Cookie |

网络失败没有服务器 code，Runtime 使用本地连接错误并保留结果未知状态。CORS、证书、地址配置和后端不可达不得归并到 `AUTHENTICATION_FAILED`。Refresh 重放沿用检测/撤销语义，最终公开为 `401 SESSION_INVALID`，不泄露内部重放分类。

## 5. 切换、刷新、退出与恢复

### 5.1 上下文切换

1. Runtime 协调其他标签页脏表单；只有取消、显式放弃或确认无未保存内容后才能发命令。未响应页按 Q8 处理。确认仅作用于当次切换，若等待期间出现新编辑或 Session 版本变化则重新确认。
2. 通过共享锁进入转换，所有标签页先停止受保护操作。选择命令绑定 Session、原 revision、目标与幂等键，后端验证当前与目标权限；无当前上下文时只核验目标。选择当前目标是无副作用 204，不推进版本、不撤销 Token。
3. 实际切换先建立可恢复根流程和签发阻断，再完成旧 Family 全部未过期 JTI 撤销交付，更新上下文/contextVersion/revision 并记录 204。数据库权威事实与 Outbox 同事务；Redis 拒绝不回滚，失败保持同键恢复，不复活旧 Token。
4. 204 表示提交完成，随后必须 refresh。目标在 refresh 前失效则结束 Session；依赖暂不可用则继续等待，不能回到旧可操作页面。刷新完成前其他切换返回 `CONTEXT_REFRESH_REQUIRED`；原键查询/重放仍稳定 204。
5. 成功刷新并核验目标后原子应用路由、公司数据与完整品牌；品牌字段或受控素材加载失败则完整回退平台品牌，不保留上一公司品牌。不得用品牌失败否认已合法建立的新工作上下文。

服务端只保证在安全检查边界拒绝旧 Token；切换前已经被后端接受并提交的业务操作不能撤回。结果晚到时前端不得填回新页面，应通过原操作句柄核查；不能承诺 AbortController 取消已提交事务。

### 5.2 刷新与限制

保留 [ADR 0026](adr/0026-authentication-token-rotation-uses-retry-aware-semantics.md) 的摘要轮换：同旧 Refresh、同幂等键、窗口内至多一次替代恢复，废止未收到的后继并撤销原响应 JTI；绝不存储或重放明文 Token。默认 Lease 5 秒、恢复窗口 10 秒且硬上限 30 秒为当前代码事实，实际环境值实施时核实，不能以本协议覆盖生产配置。

Lease 窗口内不同键返回 409，不误判为窃取；窗口外或不满足一次恢复条件的重放撤销整个 Family。Runtime 在同一个未决刷新中保留原键，有界重试；超出安全恢复能力时结束会话并重登，不能无限重试或扩大宽限窗口。

普通与无权限/待选择会话沿用绝对 8 小时、闲置 30 分钟；仅明确认证活动更新使用时间。只读上下文核验不续期，不定时刷新来无限保活。Initial Credential Session 不能通过 refresh 延长；首次改密成功必须重新登录。Password Setup 继续一次消费、只为从未拥有 Credential 的 Identity 建立密码；失去成功响应也不得换一个新 Challenge 当作密码重置。

### 5.3 整体退出

退出意图先落本地非敏感 `logoutPending`，立即遮蔽私有内容并停止新业务请求，再请求服务器。服务端持久化原 Session 的结束流程与禁止签发状态，撤销该 Family 的全部未过期 JTI，并确认验证端能拒绝后才返回 204。当前上下文、所有标签页及未选择/无权限/首次改密阶段均使用同一个结束入口。

503/网络失败保留定位 Cookie 和恢复所需服务端流程，不清 Cookie 当作成功；同键恢复原流程。浏览器只持久化退出标记、原非敏感 revision/操作键，不保存 Identity 或凭据。页面重开只允许完成退出。即使 Refresh Cookie 丢失，受来源保护的 Slot 定位和原键也能继续结束原 Session。

已完成的旧退出重试稳定 204，不能结束后来建立的 Family；无 Session 的退出幂等成功。若新 revision 已属于另一个 Session 且原键无法识别，则返回 412，不擅自退出新账号。用户随后明确选择结束当前会话时使用新的键和最新 revision。

## 6. Runtime 与浏览器安全

- 每个页面 Realm 一个无 UI 认证 Runtime、一个类型化 HTTP Client。Token 仅保存在内存；不得写 localStorage、sessionStorage、IndexedDB、URL、日志、错误上报或 Service Worker 缓存。Remote 不能读凭据、创建 Runtime 或任意发带凭据请求。
- 同 Origin 的 Web Lock 以已验证 API Origin + 统一 Slot 名命名，覆盖 bootstrap、登录、刷新、切换、退出、首次改密。Web Locks 的协调范围是同 Origin，见 [W3C Web Locks](https://www.w3.org/TR/web-locks/)；不能宣称它能协调旧平台 Origin 与新 Console。
- 广播仅允许会话代次、转换/结束消息、短期内存 Token及其关联版本；不广播邮箱、密码、公司列表、品牌、Problem或恢复材料。非敏感脏状态与标签页存活用于切换确认，不广播表单内容。Token 交接不得持久化。
- 本地持久化仅允许协调代次、未完成退出标记与其非敏感操作键。每个请求捕获 Session/上下文/读取代次，响应必须仍匹配才能回填；接到较新会话消息先清空旧数据和恢复材料，再读取自己的权威 Snapshot，不从广播列表授权。
- 协调能力异常时进入明确的恢复界面；首次无 Cookie bootstrap 与登录不得在不能保证初始化串行化时并发执行。已有 Slot 的服务器锁、Lease、revision 和退出恢复仍是最终边界，前端不能自制互斥或忽略保护继续工作。
- 恢复焦点、从休眠返回、收到上下文变更时先核验再显示或提交。保留 Tenant 闲置页面 30 秒内发现访问失效的既有要求，核验只读、不延寿；网络不可判定时遮蔽受保护内容。
- 统一浏览器入口为 `https://console.<root>`，API 为 `https://api.<root>`；保留受控静态 Remote 与攻击来源负例。v2 Cookie 接口精确限定 Console Origin，不能以浏览器传入的 Origin 推断 Platform Role。同一浏览器会话指共享这些 Cookie 和受控站点存储的浏览器分区；不使用设备指纹追踪用户清空全部站点数据后的身份。
- Cookie 读取要求精确来源；所有变更要求 JSON、`X-SF-CSRF: 1`、精确 Origin、Fetch Metadata 校验。CORS 只允许固定 Console、凭据与明确方法/头（含 If-Match），不允许任意外域或 `*` 携凭据；跨站、缺失/非法来源失败关闭。
- API 目标为开发者配置的已启动 Gateway；不把目标 URL 变成业务调用参数。浏览器 Cookie、Origin、Fetch Metadata、Authorization 均由浏览器/共享 Client 管理。部署安全边界仍遵守 rootDomain 与受控发布规定，不能为本地直连放宽。

## 7. 兼容、退役与回退

### 7.1 协议兼容边界

保留全部历史 v1 baseline、v1 schema 和已执行 SQL 原文。新认证使用显式 v2，旧 v1 普通业务 API 继续以其正式 operation 提供服务；Client 固定版本同时包含需消费的 v2 认证与现有业务接口。不能向旧 AuthenticationResult 强塞新 state/Token 含义，也不能新增 ADR 0038 的豁免。

**停止旧登录是终止旧认证协议支持的破坏性发布，不是 v1 兼容更新。** 停用事实和预期错误进入明确的协议退役发布清单；不能靠兼容测试通过或修改基线掩盖。当前消费者在启用退役之前保持原行为；本票仅新增文档，因此现行可运行组合不变。

退役范围包括旧登录、刷新、Context 选择、Tenant Switch、旧首次改密及旧 Session/Context 读取；它们不能再签发、恢复或授权旧浏览器 Session。退役层返回稳定 `410 AUTH_PROTOCOL_RETIRED`（无 Token、不改新 Cookie），旧入口引导统一 Console。旧退出保留为只撤销、不创建会话的清理入口；旧 Password Setup 入口可引导新页面，单次 Challenge 本身不失效，服务端新 operation 继续消费原 Challenge。OAuth Client Credentials、JWKS 与服务 Token 不属于浏览器协议退役范围。

### 7.2 发布顺序

1. **准备制品**：形成正式 v2 OpenAPI、生成模型、Client、route catalog 和安全策略；后端保持旧路径原状且 v2 尚不对正式用户启用。独立检查 v1 baseline 不变、v2 安全/生成契约、业务 v1 对新 Token 的接受与拒绝边界。
2. **前向扩展存储**：新增更高版本 Flyway 迁移，区分旧/新协议 Family、统一 Slot、修订号、转换/退出恢复记录，并接入 Issuance、Fence 与 Outbox。旧数据明确标为 legacy，不把旧 Family 批量转换为双权限 Family；旧 enum/CHECK 变更只能在新迁移中进行。下游不得继续运行无法识别新协议/撤销的版本。
3. **提供兼容后端与固定 Client**：先部署能运行 v2 的 Gateway、IAM、Token 验证端，再提供可安装的固定版本 Client。前端锁定版本，在隔离的已启动目标环境执行实际页面验证；不要求两个仓库日常同时发布。
4. **发布前清单**：记录后端/前端/Client 版本、目标环境、旧消费者（包括外部情况未知）、停用影响与回退版本。无法核实的外部消费者风险必须明示，不因 Q7 猜测它们不存在。此时旧前端与新前端不能同时作为该环境正式入口。
5. **受控切换**：先暂停旧认证的新建/刷新和新入口放行，排空在途旧认证请求；部署退役策略，使所有验证端拒绝 legacy User Token，建立旧 Family 签发阻断并完成可恢复撤销。确认旧 Token 在 Gateway 与业务边界均被拒绝后才打开新版入口；旧地址只引导新版。不能只换静态页面、清 Cookie 或等待 JWT 自然到期。
6. **首次新入口清理**：来源保护的 bootstrap 识别当前浏览器的两枚旧 Refresh Cookie及更早单 Cookie，幂等定位并结束对应旧 Family后清除；清理未知时 503，不先建立新会话。停止旧协议使其他旧浏览器也必须转入新版重新登录，这是该环境的协议退役影响；普通退出/当前权限失效仍只作用于本浏览器 Session。
7. **记录组合与收尾**：关联同轮后端/前端证据，确认没有旧签发、静态 Remote和原操作恢复仍可用后，再由后续任务清理旧源码/验证入口。#190–#200 保持 NOT_PLANNED，不以删除旧测试代替迁移独有检查。

新 Token 必须带可验证的协议标识，旧 Token 缺少标识即 legacy；Gateway 与所有直接接收 User Token 的服务同步执行退役边界，Service Token 独立处理。停用配置属于受控部署安全边界，不能热更新绕过滚动验证。实际版本号、迁移号、生产配置值、registry 与凭据在实施中核实，本协议不虚构。

### 7.3 回退条件

- 切换前任一必要检查失败：不开放新版，原环境继续运行；已执行的新增迁移不删除、不改 checksum。
- 切换后：只回退至明确支持已发布 v2、当前存储与退役策略的可用后端/前端/Client 组合；已经撤销的 Family/JTI 仍撤销。前端可独立回退到兼容 v2 的版本。
- 没有兼容组合：入口维护，阻止新登录与业务操作，执行修复。不得恢复数据库旧快照来复活会话，也不得自动重开旧认证。确需重开旧登录时单独说明影响并取得授权，不由本票默认允许。
- 发布前必须验证“失败停止/维护”和兼容回退路径。仅代码能启动或配置语法通过，不是协议退役或回退验证完成。

## 8. 公开请求/结果案例与后续验收

下表是**待实施的契约验收案例**，不是已运行测试。所有 Token、Cookie 均由公开流程获得，禁止直接写数据库制造成功会话；后台故障注入仅在已授权隔离环境执行。

| 场景与请求 | 后端必须给出的公开结果 | 前端必须给出的可见行为 |
|---|---|---|
| 平台身份 login，只有平台权限 | 200 AUTHENTICATED/Platform，只有一个 Family | 直接进入平台；无公司卡片 |
| 租户身份 login，只有一家公司 | 200 AUTHENTICATED/Tenant，Membership/Tenant 均经验证 | 直接进入该公司 |
| 双身份或多家公司 login | 200 CONTEXT_SELECTION_REQUIRED，无 Token；完整分组候选 | 平台管理与公司工作台同页卡片；公司直接可选 |
| 无权限 login → GET contexts → 获授权后重新检查 | 200 NO_AVAILABLE_CONTEXT；候选后续可变，读取不发 Token | 等待页；一个候选走选择命令，多个候选展示卡片 |
| 初始凭据 login → 强行选择平台/GET contexts/调用管理 API | PASSWORD_CHANGE_REQUIRED；选择/候选 403，业务 API 无业务凭证 401 | 只显示改密/退出，不出现管理菜单 |
| 首次改密成功，再重放同键/旧凭据；Password Setup 重复消费 | 首次改密同键稳定 204但无 Token；旧凭据拒绝；Challenge 不可再次建立密码 | 回登录，不自动进入管理页，不把重复消费当重置 |
| 只有平台权限，提交他人 membershipId | 403 TARGET_CONTEXT_UNAVAILABLE；无新 Tenant Token，原平台权限不扩张 | 更新候选；不能靠改卡片或路由进入公司 |
| 当前甲公司有效，目标乙无权/冻结 | 403 TARGET_CONTEXT_UNAVAILABLE；当前 Session 保留 | 不显示可操作乙页面；核验甲后才恢复 |
| 当前甲被冻结，仍有平台权限，refresh/选择平台 | 403 CURRENT_CONTEXT_REVOKED，原 Family 全部 Token 拒绝 | 所有标签页退出；重新登录才可进入平台 |
| 无关公司的 Membership 失效，当前平台仍有效 | 200 当前平台有效；新候选不含该公司 | 平台继续使用，不能再选失效公司 |
| 两标签同旧 Refresh 不同键并发 | Lease 内一个轮换，另一个409；不误撤销 Family | 协调并有界恢复，不重复提示密码错误 |
| 同旧 Refresh 同键丢失结果后恢复一次，再重放 | 一次替代恢复；再次/超窗重放401并撤销全部JTI | 停止使用旧结果，必要时重新登录 |
| 甲→乙选择204，refresh返回503，再恢复 | 旧 Token 拒绝；原选择键仍204；恢复后只发乙Token | 持续等待，绝不恢复可操作甲页面 |
| 甲业务响应在切乙后返回 | 响应可能对应切换前已接受的操作；不能据此改变新权限 | 丢弃旧页面回填；按原操作身份核查未知提交 |
| 退出A成功→登录B→A刷新/退出响应迟到 | A Token拒绝；旧退出键只对应A；Cookie竞争不恢复A | 不回填A数据，Cookie错配进入结束后重登流程 |
| 两标签同时登录不同账号 | Slot串行化，至多一次建立；另一请求412/409 | 所有页面只展示同一当前Identity |
| 退出时断网/Redis或DB失败，关闭重开页面 | 未确认前503/未知；同键可继续，不因清Cookie丢失根流程 | 所有私有内容清除，退出待完成，不能换账号 |
| 原操作者A的私有恢复句柄被B提交 | 服务端拒绝原操作者不匹配，即使B也有平台权限 | 换账号清除句柄、一次展示Secret与返回路径 |
| 后台脏表单存在或标签页无响应时切换 | 未确认前不发选择命令；服务端不依赖脏状态授权 | 可取消或显式放弃，恢复页不提交旧Tenant表单 |
| 非Console Origin/跨站/缺CSRF/非法Content-Type | 403 BROWSER_REQUEST_REJECTED；不设置新凭据 | 明确环境/来源问题，不误报密码错误 |
| 新入口发现旧双Cookie，旧页面继续refresh/login | 清理确认后新登录；已退役旧认证410；旧Token业务请求拒绝 | 引导新版，不残留另一份可恢复登录 |

后端验收覆盖真实 IAM↔Tenant Access、PostgreSQL、Redis、Gateway 与 Token 验证端，尤其撤销、Fence、退役边界、旧 Cookie 晚到和迁移兼容。模拟仅补充超时/乱序/提交故障，不替代真实授权拒绝。

前端在桌面 Chrome 连接已启动后端，验证分组卡片、完整公司列表、多标签页、未保存保护、恢复/换账号、受控 HTTPS/CORS/CSRF、平台/Tenant 品牌、键盘焦点、可访问名称、中英文及精确数字日期展示。后端 JDK 17；Chromium 可日常测试，但不扩大产品兼容承诺。按 #201 责任拆分证据，普通本地任务不默认启动完整 Compose。

## 9. 决策同步与完成条件

[ADR 0052](adr/0052-unified-console-authentication-uses-versioned-session-protocol.md) 承载本方案；ADR 0027、0038、0039、0051 的冲突部分标注适用范围，ADR 0031 的“Platform必须重新以Tenant意图登录”和“仅Tenant间切换”仅属于旧协议。身份/租户权威分工、每Realm一个Runtime、正式Client、Cookie/CSRF、单次凭据及历史基线不可变继续有效。

本票的设计验收见 [Issue #202 设计验收记录](acceptance/issue-202-unified-authentication-protocol.md)。用户已确认完整方案，文档链接、JSON 示例与案例一致性检查通过；正式制品、历史基线、运行代码和迁移未变。Issue 完成记录逐项映射验收标准，并保留尚未实现及后续验证的边界。无需先实现业务代码，但不能把本协议或未来测试清单当作业务验收证据。
