# 账号安全规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-04 的设计拷问逐条确认（Q3–Q22），MFA 模型由 [ADR-0019](../../docs/adr/0019-mfa-per-identity-enforced-at-token-issuance.md) 记录。

## 1. 背景与目标

- 登录只校验 First Factor：Tenant Realm 支持密码与邮箱/手机验证码，Platform Realm 只支持密码。
- `forge.iam.platform.mfa-required` 是半成品：开启后拒绝所有 Operator 登录（`OperatorSessionService`）。
- 认证失败没有计数与锁定；验证码只有发送间隔。
- 密码规则只有注册接口的长度校验（8–72）；Identity 不能自助改密码或找回密码，Operator 只能由管理员重置。

目标：两个 Realm 都支持基于 TOTP 的 MFA 与 Recovery Code，Tenant 可提出 MFA Requirement；认证失败有 Login Lockout；按 Realm 执行 Password Policy，并补齐自助改密码与忘记密码。

非目标：

- IP 白名单、企业 SSO、短信/邮件作为第二因素、WebAuthn/Passkey。
- 进入租户时的 step-up 补验（ADR-0019）。
- 定期强制改密码、强制字符组合、存量弱密码的强制修改。
- 自助 MFA Reset。

## 2. MFA

### 2.1 Authenticator 与 TOTP

- Identity 与 Operator 各自最多一个 Authenticator，分表存储（ADR-0003）；Identity 的 Authenticator 在其全部 Tenant 通用。
- TOTP 取 RFC 6238 标准参数：SHA-1、6 位、30 秒步长，接受前后各 1 个步长的时钟误差。同一动态码在有效期内只能用一次（防重放）。
- TOTP 密钥用 AES-GCM 加密入库，加密密钥从配置读取，不设默认值，漏配时启动失败。

### 2.2 绑定、解绑与 Recovery Code

- 绑定：服务端生成密钥并返回 `otpauth://` URI（前端渲染二维码）；用户提交首个动态码校验通过后才生效。生效时生成 10 个 Recovery Code，只在本次响应中返回，服务端只存哈希。
- Recovery Code 可在 MFA 步骤中代替动态码，用后作废。已登录时再验证一次动态码可重新生成一组，旧组全部作废。
- 解绑：Identity 验证一次动态码后可自助解绑；之后进不了有 MFA Requirement 的 Tenant，不另加阻止。`mfa-required=true` 时 Operator 不能解绑，只能更换（先验证旧动态码，再按绑定流程绑定新的）。

### 2.3 登录流程

- 两个 Realm 的登录都分两步：First Factor 通过后，若已绑定 Authenticator，登录会话进入「待 MFA」状态，提交正确的动态码或 Recovery Code 后才算认证成功，之后才能走授权码流程。待 MFA 状态有有效期（与验证码有效期同量级），过期需重新输入 First Factor。
- Tenant Realm 的密码登录与验证码登录同样适用（First Factor 不论类型）。
- 授权记录本次认证是否完成 MFA；后续 refresh 与 switch 依据授权上的记录判断，而不是依据登录会话（ADR-0019）。
- Operator，`mfa-required=true` 且未绑定：通过密码后进入「必须绑定」状态，当场完成绑定并输入首个动态码即认证成功，不需要管理员介入。默认 `false`，部署配置中开启；原「开启即拒绝全部登录」的逻辑删除。

### 2.4 MFA Requirement

- 每个 Tenant 一个开关，默认关闭，存 iam。设置需要新增的权限码 `iam:security:manage`（Tenant Admin 预置拥有），不受 Plan 限制。
- 执行点：签发进入该 Tenant 的 Token 时（exchange、switch、refresh），若授权未完成 MFA，拒绝签发并返回错误码 `auth.mfa_required`。开启后存量未绑定成员在下一次刷新时被拦，不做即时吊销。
- 被拦的 Identity 可在当前会话中当场绑定（登录会话或 Access Token 均可调用绑定接口），绑定成功后该会话与当前授权视为已完成 MFA，随后重试进入。

### 2.5 MFA Reset

- Identity：持有新增平台权限码 `platform:identity-mfa:reset` 的 Operator 执行。Operator：由 Super Admin 执行。
- 清除 Authenticator 与全部 Recovery Code，写审计事件；不提供凭邮箱或短信验证码的自助重置。
- 平台提供按登录标识（账号、手机号、邮箱）精确查找 Identity 的接口，只返回 Identity 标识、展示名与是否已绑定 Authenticator，不列表浏览、不返回 Membership。

## 3. Login Lockout

- 第一因素失败按规范化后的登录标识（账号、手机号、邮箱）计数，不论该标识是否存在；动态码与 Recovery Code 失败计在对应的 Identity 或 Operator 上。
- 时间窗口内失败达到阈值即临时锁定，锁定期内拒绝该标识的全部认证，到期自动解除，不存在永久锁定。锁定时返回错误码 `auth.locked`，携带剩余等待时间；由于不存在的标识同样计数，锁定提示不泄露账号是否存在。
- 另按客户端 IP 限速，防止撞库。
- 阈值、窗口、锁定时长、IP 限速按 Realm 分别配置（`@ConfigurationProperties` record），参考默认：15 分钟内 5 次失败锁定 15 分钟。
- 计数存 Redis。

## 4. Password Policy

- 每个 Realm 一套，由平台配置，Tenant 不能自定（GLOSSARY「Password Policy」）。
- 规则（参照 NIST 800-63B）：长度 8–72（下限可配）；拒绝内置弱密码清单中的密码（约 1 万条，随 iam 资源打包，启动时加载，不调用外部服务）；拒绝与登录标识相同的密码。不强制字符组合，不强制定期修改。
- 执行入口：注册、自助改密码、忘记密码重设、管理员重置 Operator 密码。存量密码不强制修改。
- 违反时返回 `password.*` 错误码（如 `password.too_short`、`password.too_common`），文案经前端 i18n 展示。

## 5. 自助改密码与忘记密码

- Identity 改密码：需原密码。Operator 改密码：需原密码。
- Identity 忘记密码：向已登记的手机号或邮箱发验证码（复用 `VerificationCodeService`，新增用途），校验通过后设新密码；已绑定 Authenticator 时还须完成 MFA。响应对未注册目标与已注册目标一致。
- 改密码或重设成功后，吊销该账号的其他会话与 Refresh Token，并使已签发的 Access Token 失效。Operator 侧沿用管理员重置密码现有的失效机制；Identity 侧的实现方式在工单中确认。
- 上述入口都计入 Login Lockout（原密码错误、验证码错误、动态码错误）。

## 6. 审计事件

沿用 `领域.动作` 命名：

| 事件 | 说明 |
|---|---|
| `auth.mfa` | MFA 步骤成功或失败，结果中区分动态码与 Recovery Code |
| `auth.locked` | 触发 Login Lockout |
| `mfa.bind` / `mfa.unbind` / `mfa.recoveryCodesRegenerate` | 持有者自助操作 |
| `mfa.reset` | Operator 执行的 MFA Reset，资源为 Identity 或 Operator |
| `password.change` / `password.reset` | 自助改密码、忘记密码重设 |
| `tenantSecurity.update` | MFA Requirement 变更 |

## 7. 前端

tenant-app：

- 登录页：MFA 步骤（可切换为 Recovery Code）；遇到 `auth.mfa_required` 时的强制绑定步骤。
- 忘记密码页。
- 「账号安全」个人页：改密码、绑定或解绑 Authenticator、重新生成 Recovery Code（Recovery Code 只展示一次，提供复制与下载）。
- 租户管理「安全设置」页：MFA Requirement 开关，需 `iam:security:manage`。

platform-console：

- 登录页：MFA 步骤与强制绑定步骤。
- 「账号安全」个人页：改密码、更换 Authenticator、重新生成 Recovery Code。
- 运营人员管理：「重置 MFA」操作。
- 「身份查询」页：按登录标识精确查找，展示是否已绑定，提供「重置 MFA」，需 `platform:identity-mfa:reset`。

## 8. 验收

- 两个 Realm：未绑定时登录行为不变；绑定后密码登录与验证码登录都必须完成 MFA；Recovery Code 只能用一次；同一动态码不能重放。
- Operator `mfa-required=true` 时未绑定者在登录中当场绑定后进入；已绑定者不能解绑。
- Tenant 开启 MFA Requirement 后，未完成 MFA 的授权在 exchange、switch、refresh 时都被拒（`auth.mfa_required`）；当场绑定后可进入。
- 不存在的登录标识与存在的标识在失败计数、锁定提示上表现一致；锁定到期自动解除。
- Password Policy 在四个入口一致生效；改密码后其他会话与 Refresh Token 失效。
- 审计事件按第 6 节记录；租户管理员无法重置成员 MFA；MFA 密钥漏配时 iam 启动失败。
