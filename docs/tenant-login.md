# Tenant Realm 最小登录

工单：[03](../.scratch/saas-forge-mvp/issues/03-tenant-realm-minimal-login.md)。Identity 是全局身份，Membership 绑定 Identity 与 Tenant。多租户选择与切换见工单 [06](../.scratch/saas-forge-mvp/issues/06-multi-tenant-membership-switch.md) 与下文「多租户」一节。

## 本地运行

按 README 启动依赖和构建后，在**仓库根目录**启动 IAM 并显式启用演示数据：

```bash
java -jar backend/forge-iam/target/forge-iam-0.1.0.jar --spring.profiles.active=demo
```

`demo` profile 在普通迁移之外加载 `db/demo` 的 Flyway 演示数据。仅用于独立本地数据库，不要在共享环境启用。账号 `demo`，邮箱 `demo@example.com`，密码 `Demo-password-123!`。不启用此 profile 时只创建表，不插入已知密码的身份。

在 Cursor / VS Code 中选择 `Backend: IAM (demo)` 启动本地演示环境。仓库仅保留这一个 IAM 调试配置，避免 Java 插件按主类匹配时选到不带 profile 的重复配置。未执行过演示迁移的数据库如需不加载演示数据，可在终端使用不带 `--spring.profiles.active=demo` 的启动命令。

同一个本地数据库执行过 `demo` 迁移后，后续启动仍需启用 `demo` profile；否则 Flyway 会报 `Detected applied migration not resolved locally: demo identity`，并连带导致 `sqlSessionTemplate`、`identityMapper` 创建失败。使用上面的启动命令或演示启动项即可恢复迁移目录，无需执行 `repair` 或关闭校验。

按 README 启动 demo-service 和网关，然后在 `frontend` 运行 `pnpm dev:tenant`，访问 <http://localhost:5174>。登录区展示当前身份、租户，以及 demo-service 验签后返回的三个 ID。原有子应用技术验证区域仍保留。

Vite 将 `/iam` 与 `/demo` 代理到本地网关 `http://localhost:8080`。部署前端时需由同源反向代理提供这两个路径。

## 接口与验证边界

`/auth/*` 与 `/demo/*` 的成功与失败响应均为统一信封（[ADR-0015](adr/0015-unified-api-envelope.md)）：下表「结果」列描述的是 `data` 的内容，错误以 `code` 区分；`/oauth2/*` 保持 RFC 6749 / OIDC 原格式。`/auth/*` 的全部响应（含安全拒绝与错误信封）都带 `Cache-Control: no-store`；401、403 保留 RFC 6750 的 `WWW-Authenticate` 质询头。

| 接口（网关路径） | 请求 | 结果 |
| --- | --- | --- |
| `POST /iam/auth/login` | JSON `account`、`password` | Redis 登录会话 Cookie、`clientId`、`redirectUri`、`memberships`（可进入的租户） |
| `POST /iam/auth/login/send-code` | JSON `target`（邮箱或手机号） | `expiresIn`、`resendIn`（秒）；目标未注册时响应相同但不发送 |
| `POST /iam/auth/code-login` | JSON `target`、`code` | 与 `/auth/login` 相同：登录会话 Cookie、`clientId`、`redirectUri`、`memberships` |
| `POST /iam/auth/register/send-code` | JSON `target` | `expiresIn`、`resendIn`；已被注册时 409 `registration.contactTaken` |
| `POST /iam/auth/register` | JSON `account`、`password`、`target`、`code`，可选 `displayName` | 201，`identityId`；不建立登录会话、不加入任何租户 |
| `GET /iam/oauth2/authorize` | 标准 authorization_code + S256 PKCE 参数 | 重定向至注册回调，携带一次性 code 和 state |
| `POST /iam/auth/exchange` | JSON `code`、`codeVerifier`，可选 `membershipId` | 服务端经标准 Token 端点交换，返回 `accessToken`、`refreshToken`、`tokenType`、`expiresIn`、`identityName`、`tenantId`、`tenantName`、`membershipId` |
| `POST /iam/auth/logout` | 同源 JSON `{}` + IAM 会话 Cookie | 销毁当前浏览器的 Redis 登录会话，返回 200，`data` 为空 |
| `POST /iam/auth/refresh` | JSON `refreshToken` | 新的一对 Token；旧 Refresh Token 重放会撤销同一授权的后续刷新能力 |
| `POST /iam/auth/switch` | JSON `refreshToken`、`membershipId` | 绑定目标 Membership 的新一对 Token；目标不可进入时 403 `auth.tenantAccessDenied` |
| `GET /iam/auth/memberships` | `Authorization: Bearer …` | 当前身份的有效 Membership：`membershipId`、`tenantId`、`tenantName` |
| `GET /iam/oauth2/jwks` | 无 | Spring Authorization Server 发布的公钥 |
| `GET /demo/context` | `Authorization: Bearer …` | `identityId`、`tenantId`、`membershipId`、`version` |

登录使用标准授权码 + S256 PKCE：JSON 凭证入口建立登录会话；SDK 生成 verifier 和 state，经 `/oauth2/authorize` 获取授权码，并校验回调 state。首版 scope 为 `tenant`，不签发 OIDC ID Token，也不启用 password grant。

`/auth/exchange` 与 `/auth/refresh` 是匿名开放的 Token 交换适配器，持有服务端 client secret 并调用 IAM 内部 `/oauth2/token`。标准端点负责 PKCE 校验、Token 签发与刷新轮换，网关不再开放 `/iam/oauth2/token`。部署时 IAM 监听端口只应供内部服务访问。

**适配器没有认证浏览器客户端。** 任何持有 code + verifier 或 Refresh Token 的调用者都可以通过适配器换取 Token。因此浏览器侧仍按公开客户端管理风险；“浏览器不持有 client secret”只说明密钥存放位置，不能防止浏览器凭证被窃取后使用。这也不是 BFF：Token 仍交给浏览器保存。使用适配器是为了保留框架默认 Refresh Token 签发流程；已核对本机 Spring Security 7.0.8 的 `OAuth2RefreshTokenGenerator`，授权码流程使用 `ClientAuthenticationMethod.NONE` 时默认返回 null。

Access Token 有效期 15 分钟，包含 `sub`、`tenant_id`、`membership_id`、`ver`，audience 为 `tenant-app`。网关和 SDK 都检查签名、issuer、有效期、audience 和必要上下文；客户端租户请求头不能覆盖 JWT。平台路由 `/platform/**` 只接受 Platform Realm Token，Tenant Token 访问返回 401，见 [Platform Realm 与运营登录](platform-login.md)。

Redis 保存 Spring Session 登录会话和标准 `OAuth2Authorization`，刷新凭证有效期 7 天。授权码/Token 的索引使用 SHA-256；认证前的索引和会话统一使用 `iam:global:` 前缀，因查询前还没有可相信的租户标识。授权状态采用 Spring 的 JDK 序列化器，只连接受信任的 Redis。Token 端点按授权 ID 使用 Redis 互斥，避免同一授权的凭证并发消费。重放撤销不等待这把锁；在途换码或刷新只能原子更新仍然存在的授权，不能重新创建被撤销的授权。标准提供者负责授权码失效和刷新轮换；旧 Refresh Token 的摘要保留至其原始过期时间，检测到重放就删除该授权及当前 Token 索引，使其后续刷新失败。这也意味着丢失刷新响应后重试旧凭证会要求重新登录。刷新时重新查询原会话绑定的身份、租户和 Membership，不能隐式切换租户。Redis 故障时登录或刷新失败，返回 503 `system.unavailable`（包括在过滤器层访问 Redis 失败、经容器转发到 `/error` 的情况），前端据此保留会话。停用 Membership 或租户后 Access Token 的即时失效见下文「即时失效」；刷新凭证被拒绝（401）后前端清除本地凭证。

当前重放检测**没有宽限期**。同一浏览器的标签页共享一个 Refresh Token（见下文「会话存储」）；若两个标签页并发刷新，后到的一方使用已轮换的旧凭证，就会撤销整条授权的后续刷新能力，所有标签页都需要重新登录。为此 SDK 让刷新与切换租户在各标签页之间串行，取得锁后发现凭证已被其他标签页轮换就直接采用新会话（[ADR-0017](adr/0017-local-storage-session-cross-tab.md)）。其他浏览器或独立客户端持有同一凭证时不受此协调，仍按重放撤销。暂不加入宽限期，以保持旧凭证一经识别为重放即撤销的语义。

每次轮换保留一个 `:used-refresh` 摘要键，TTL 截止该旧凭证原来的过期时间，最长约 7 天；撤销授权后历史摘要也按各自 TTL 自然过期。按每 15 分钟刷新一次、连续活跃 7 天估算，一条授权约积累 672 个历史键；主动调用刷新接口会更多，因此这不是数量上限。运维需结合实际刷新频率关注键数、TTL、Redis 内存和淘汰情况，不能通过提前删除摘要来降低内存占用，否则会丢失重放关联。工单 07 评估了按授权聚合（每条授权一个以过期时间为分值的有序集合，轮换时顺带清理已过期成员）：重放检测要从被重放的凭证反查授权，而标准 Refresh Token 是不含授权 ID 的随机值，聚合必须改写 Refresh Token 生成器把授权 ID 编入凭证，并兼容已签发的旧格式凭证。键数虽减少，总条目与内存量级不变（摘要仍需逐条保留到原始过期时间），收益有限，因此保持现有存储结构，以上运维关注点不变。

`@crane199709/sdk` 的 `createTenantClient(localStorage)` 提供登录、退出、刷新、同源请求和会话读取。业务调用用 `api<T>()` 直接取信封的 `data`、`page<T>()` 取 `{ items, page }`，失败抛出带 `code`、`httpStatus`、`userTip`、`traceId`、`errors` 的 `ApiError`；响应不是信封（如代理返回的 HTML）时为 `system.error`。`request()` 仍返回原始 `Response`。界面错误提示优先展示 `userTip`，并附可复制的 traceId。Shell 的 `main.ts` 创建唯一实例并 `provide(tenantClientKey, client)`；登录等页面与 Sub-App 通过 `inject(tenantClientKey)` 获取同一实例，不再自行创建客户端。注入键携带 `InjectionKey<TenantClient>` 类型信息。并发刷新在这个共享实例内合并，各标签页之间经 Web Locks 串行。demo-service `/context` 的响应类型由 tenant-app 调用方维护，不由通用 SDK 导出。

### 会话存储

Token 保存在 `localStorage`，同一浏览器的所有标签页共享登录，关闭浏览器后在刷新凭证有效期（7 天）内仍保持登录，公用电脑上须主动退出。请求遇到 401 时合并刷新并只重试一次。生产环境必须使用 HTTPS，并防止脚本读取浏览器凭证；`localStorage` 保留时间更长，凭证泄漏的影响窗口也更长。从 `sessionStorage` 升级时不迁移旧会话，用户重新登录一次即可。

SDK 监听 `storage` 事件跟随其他标签页（决策见 [ADR-0017](adr/0017-local-storage-session-cross-tab.md)）：

| 其他标签页的操作 | 本标签页的行为 |
| --- | --- |
| 刷新 Token（Membership 不变） | `onSessionChange` 订阅者收到新会话（`change.crossTab` 为 true），权限与菜单按新 Token 重新读取，页面不重载 |
| 切换租户 | 在途请求以 `auth.sessionChanged` 失败；Shell 卸载全部 Sub-App、清空共享 Pinia 状态后回首页 |
| 退出或会话被清除 | 同上，回到登录 |
| 登录 | 停留在登录的标签页进入首页 |

所有标签页始终处于同一个租户，不支持不同标签页同时停留在不同租户。刷新（`refresh`）与切换（`switchTenant`）在标签页内串行队列之外，再以 `navigator.locks.request('forge.tenant.rotation', ...)` 跨标签页互斥。

「重新登录」调用 `/auth/logout`，清除本地凭证并等待服务端销毁 IAM 会话；失败时显示“浏览器仍可能保持登录”并允许重试。此接口只接受 JSON，不启用跨域访问。IAM 登录会话的空闲超时为 30 分钟（访问可延长会话）；未退出时，同一浏览器可用新 PKCE 再次授权而不输入密码，这是保留的 SSO 行为。**关闭标签页或单独调用 SDK `clear()` 不会退出 IAM 会话**。退出会清除所有标签页共享的本地凭证，其他标签页随之回到登录；但不撤销已签发的 Access/Refresh Token，已泄漏 Access Token 在过期前仍可通过网关。即时失效按 Membership 与租户生效，不能单独吊销某一个 Token。

账号区分大小写，邮箱按不区分大小写匹配并保持唯一；账号禁止 `@`，邮箱必须包含非空的 `@` 两侧，避免两个登录标识命名空间重叠。V3 迁移只增加约束，不自动改写历史数据；已有非法邮箱或仅大小写不同的重复邮箱会使迁移失败，需要先由维护者处理。

## 自主注册与验证码登录

工单：[10](../.scratch/saas-forge-mvp/issues/10-self-registration-verification-code.md)。注册只创建全局 Identity，必须验证邮箱或手机之一，另一项留空；账号以字母开头、不含 `@`。账号、邮箱（不区分大小写）、手机号在未删除的身份中全局唯一，冲突返回 409 `registration.accountTaken` / `registration.contactTaken`。注册不加入任何租户，tenant-app 注册后用同一账号密码登录，进入无租户页。手机号只校验 `+` 可选、6–15 位数字的格式，不做国家码归一化，`+8613800000000` 与 `13800000000` 视为不同号码。

验证码登录认证的是同一个全局 Identity，建立与密码登录相同的登录会话，之后的授权码 + PKCE、`/auth/exchange` 与 Token 声明完全一致。密码登录仍只接受账号或邮箱。

验证码规则（默认值可通过 `forge.iam.verification.*` 配置）：

| 规则 | 默认 | 超出时 |
| --- | --- | --- |
| 有效期 `code-ttl` | 5 分钟，一次性使用 | 400 `verification.codeExpired` |
| 发送间隔 `resend-interval` | 60 秒，按目标计算、不区分注册与登录 | 429 `verification.tooFrequent` |
| 错误次数上限 `max-attempts` | 5 次，达到即作废，需重新获取 | 第 5 次 400 `verification.attemptsExceeded`，之后 `verification.codeExpired` |

重新获取会覆盖同用途的旧码并清零错误次数。验证码状态只在 Redis（`iam:global:verification:` 前缀），明文存放：6 位数字的取值空间很小，哈希不能防住拿到 Redis 数据的人，防猜测依靠错误次数上限。为不暴露账号是否存在，登录验证码对未注册目标同样生成、计入发送间隔，只是不发送，因此响应和猜测时的错误与已注册目标一致；注册时则如实提示邮箱或手机号已被占用（提交注册时同样会提示，提前隐藏没有意义）。这里只按目标限频。网关按 Token 中的租户限流（工单 11），只作用于已登录请求；发送验证码等匿名接口按来源 IP 的限流尚未实现。

短信与邮件发送是 SPI：声明 `io.saasforge.iam.verification.service.SmsSender` 或 `EmailSender` 类型的 Bean 即替换对应通道，两者独立。未声明时使用开发用实现，启动时写一条 WARN，并把验证码明文写入 INFO 日志，**生产环境必须提供实现**。

## 多租户

登录只认证全局 Identity，密码正确后响应列出可进入的 Membership；错误密码和未知账号继续返回同一 401。「可进入」指 Identity 与 Membership 是 `active`、所在 Tenant 是 `trial` 或 `active`，其他状态既不列出，也不能被选择或切换进入。SDK 的 `login` 据此返回三种结果：只有一个时自动进入（`signedIn`）；多个时返回 `selectTenant`，由租户选择页调用 `selectTenant(membershipId)`；没有时返回 `noTenant`，Shell 展示无租户页，不发起授权。tenant-app 的登录、选择租户、无租户分别是 `/login`、`/select-tenant`、`/no-tenant` 整页。路由守卫把未登录访问需登录页面的请求转到 `/login?redirect=<原路径>`，进入租户后回到 `redirect`（只接受站内路径，否则回首页）；页面在路由元信息中声明所需权限码，缺少时进入 `/403`。

选择租户时 `/auth/exchange` 携带 `membershipId`，适配器把它作为 Token 请求的附加参数 `membership_id` 交给标准端点，Token 定制器只在已认证 Identity 自己的有效 Membership 中查找目标，找不到则以 `access_denied` 拒绝，适配器转换为 403 `auth.tenantAccessDenied`。不带 `membershipId` 时仅当身份恰有一个有效 Membership 才签发。

切换租户（ADR-0002）就是带 `membership_id` 的一次标准 refresh_token grant：沿用同一授权的轮换、重放撤销与互斥，旧 Refresh Token 随之失效，新 Access Token 的 `tenant_id`、`membership_id`、`ver` 来自目标 Membership；之后的普通刷新留在新租户。被拒绝的切换不消费刷新凭证，会话留在原租户。下游只信任 Token，没有「按请求头临时指定租户」的分支。

`GET /auth/memberships` 由 IAM 以自身签名密钥无状态验签 Access Token（issuer、audience），不使用登录会话 Cookie；网关对该路径要求有效 Tenant Token。

前端 SDK 的刷新与切换共用一个轮换队列，切换会等待进行中的刷新后使用新 Refresh Token。切换成功后提升会话代次，切换前发出的请求以 `auth.sessionChanged` 失败，不会把旧租户响应写回界面，也不会在 401 后带新租户 Token 重发；切换只有在刷新凭证被拒绝（401 `auth.failed`）时清除会话，其他故障保留原会话。`onSessionChange` 通知订阅者。顶部租户切换器切换成功后整页回到 `/`，丢弃 Shell 与 Sub-App 中属于旧租户的内存状态和路由。选择租户依赖 IAM 登录会话（空闲 30 分钟）；在选择页刷新浏览器会回到登录页。

IAM 认证阶段 Membership 查询的 RLS 例外及边界见 [ADR-0001](adr/0001-shared-schema-tenant-isolation.md)。

## 即时失效

工单：[07](../.scratch/saas-forge-mvp/issues/07-immediate-revocation.md)，决策见 [ADR-0005](adr/0005-immediate-revocation-via-membership-version.md)。iam 在 Redis 发布以下状态，键名约定在 SDK `MembershipStateKeys`，iam 是唯一写入方：

| 键 | 值 | 写入时机 |
| --- | --- | --- |
| `iam:{tenant_id}:membership:{membership_id}:version` | 当前 `version` | 签发 Token 时；停用 Membership、角色变更时 |
| `iam:{tenant_id}:status` | Tenant Lifecycle 状态，如 `trial`、`active`、`suspended` | 签发 Token 时（仅键缺失时补写）；消费 platform 广播的 `TenantStatusChanged` 时 |
| `iam:{tenant_id}:membership:{membership_id}:grants:{version}` | 该版本的角色与权限 JSON，TTL 1 小时 | 签发 Token 时；版本递增时 |

- **网关**：验签后用一次 `MGET` 读取前两项。Token 的 `ver` 与版本不等、租户不是 `trial` 或 `active` 或任一键缺失都返回 401 `auth.unauthenticated` 信封（`WWW-Authenticate` 带 `error="invalid_token"`）；Redis 不可用时返回 503 `system.unavailable`，避免 401 触发刷新后把用户登出。业务服务仍只做离线验签，绕过网关直连业务服务的请求不受即时失效保护；但 SDK 的写入保护会读取同一租户状态，拒绝停用、过期、归档、删除租户的写入（工单 15）。
- **iam 变更入口**：`MembershipStateService.deactivate`（停用并递增版本）、`MembershipStateService.incrementVersion`（角色变更时调用）、`TenantStateService.apply`（消费 `TenantStatusChanged`，只应用比已应用版本更新的状态，在消费事务内先改库再写 Redis，Redis 失败则消息重投）。租户状态由 Operator 在 platform 变更（工单 15）；角色管理（工单 16）与成员管理（工单 17：停用、恢复、移除、主动退出、Owner 转让）在事务提交后经 `MembershipStateService.publish` / `incrementVersion` 发布新版本。变更先提交数据库再写 Redis；Redis 写入失败时调用方收到异常，刷新已按数据库拒绝，但已签发的 Access Token 在过期前仍会通过网关，需重试变更。状态必须经由这些方法修改：直接改库不会更新 Redis。
- **并发**：签发时读到的旧版本不能覆盖并发变更已发布的新版本（版本只前进，Lua 脚本比较后写入）；租户状态在签发时只补写缺失的键，不会用签发前读到的状态覆盖并发写入的停用状态。重新启用租户后，停用前签发且未过期的 Access Token 会重新可用。
- **状态丢失**：Redis 淘汰或清空后，请求先得到 401，前端刷新时 iam 按数据库重新发布状态，用户无需重新登录。
- **刷新**：Membership 或身份不再 active、租户不再是试用或正常时刷新失败（401）；版本递增后刷新签发携带新 `ver` 的 Token。切换租户不递增版本，切换前所在租户的 Access Token 在过期前仍有效。
- **角色与权限明细**不进入 JWT。业务服务注入 SDK `MembershipGrantStore`，按 Token 的 `membership_id + ver` 读取并按 Membership 本地缓存（最多 1 万条），版本变化即重新读取；明细未发布时抛出异常而不是视为无权限。角色管理（工单 16）之前发布的明细为空。
- **前端**：`@crane199709/sdk` 的请求遇到 401 时合并为一次刷新并重放一次（只合并同时在途的刷新，刷新完成后才收到 401 的旧请求会再刷新一次）。刷新凭证被拒绝（刷新接口返回任何 401，不论是否为信封；或 `auth.tenantAccessDenied`）时清除会话，Shell 回到 `/`，登录面板回到登录表单；刷新接口返回 5xx（如 IAM 依赖的 Redis 不可用时的 503 `system.unavailable`）、其他非 401 的非信封响应或网络故障时保留会话，请求以对应 `ApiError` 或原错误失败。

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `forge.security.issuer` | 无，本地值 `http://localhost:8080/iam` 写在各服务 `application.yml` | IAM、网关、业务服务必须相同；漏配则启动失败 |
| `forge.security.jwk-set-uri` | 无，本地值 `http://localhost:8101/oauth2/jwks` | 网关和业务服务的 IAM 内部地址，公钥缓存后本地验签 |
| `forge.iam.redirect-uri` | `http://localhost:5174/` | 必须与 Shell 同源；改为 127.0.0.1 访问时同步调整 |
| `forge.iam.client-secret` | 每次启动随机生成 | 服务端适配器持有；多实例需一致；不构成浏览器客户端认证 |
| `forge.iam.token-base-uri` | 按 IAM 实际监听地址、端口、TLS 和 context-path 推导 | 可显式配置内部基址（含 context-path、不含 `/oauth2/token`）；HTTPS 须通过 JVM 信任链和主机名验证 |
| `forge.iam.signing-key-file` | 无，本地值 `deploy/data/iam/signing.jwk` 写在 IAM `application.yml` | IAM 首次启动生成 RSA 私钥文件，权限 0600；后续启动复用，禁止提交；部署时指向持久化存储，多实例共用，漏配则启动失败 |
| `FORGE_REDIS_HOST` / `FORGE_REDIS_PORT` | `127.0.0.1` / `6379` | IAM 会话与即时失效状态的 Redis；网关与使用 `MembershipGrantStore` 的业务服务必须连接同一实例 |

变更端口或域名时同步调整 issuer、JWKS、注册回调 URI 和代理目标。多实例 IAM 应提供同一受保护密钥；本工单不提供密钥轮换管理。

## 验收命令

后端：`cd backend && ./mvnw verify`。IAM 测试使用临时 PostgreSQL、Redis 容器及独立签名文件；网关测试使用临时 Redis 容器；网关与 demo-service 测试经真实 HTTP/JWKS 验签，不替换 JwtDecoder。前端：`cd frontend && pnpm test && pnpm typecheck && pnpm build && pnpm format:check`。数据库迁移测试与 IAM 测试需要 Docker。


IPv6 回归（运行环境需支持 `::1`）：

```bash
cd backend
./mvnw -pl forge-iam -am '-Dtest=TenantLoginTest#emailLoginAndJwksVerifyRequiredClaims+refreshReplayRevokesTheAuthorizationFamily' -Dsurefire.failIfNoSpecifiedTests=false -Dforge.test.iam-bind-address=::1 test
```

该回归让 IAM 实际绑定 IPv6 回环地址，覆盖非默认 context-path 下的换码、JWKS 验签与刷新，不设置 `forge.iam.token-base-uri` 覆盖值。显式配置 IPv6 基址时同样需要方括号，例如 `http://[::1]:8101`。

若 JVM 配置了 HTTP 代理，还需保证实际内部地址直连。`::1` 经 Java 展开后为 `0:0:0:0:0:0:0:1`，不能只验证压缩形式的代理规则。本机隔离回归在依赖已缓存的情况下，额外传入 `-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost= '-Dhttp.nonProxyHosts=*'` 临时排除代理影响；这些参数只用于当前本地测试，不修改机器配置，也不作为生产配置建议。
