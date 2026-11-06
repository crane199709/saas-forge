# 06: Login Lockout

**What to build:** 按 [spec](../spec.md) 第 3 节，两个 Realm 的认证失败按登录标识计数并临时锁定，另按 IP 限速。

**Blocked by:** 02, 04

**Status:** resolved

- [x] First Factor 失败按规范化登录标识计数，不论标识是否存在；动态码与 Recovery Code 失败计在对应 Identity 或 Operator 上
- [x] 窗口内达到阈值即锁定，锁定期内拒绝该标识全部认证，返回 `auth.locked` 与剩余等待时间；到期自动解除
- [x] 按客户端 IP 限速
- [x] 阈值、窗口、锁定时长、IP 限速按 Realm 配置（`@ConfigurationProperties` record），参考默认 15 分钟 5 次锁 15 分钟；计数存 Redis
- [x] 审计 `auth.locked`
- [x] 测试覆盖：存在与不存在的标识表现一致、到期解锁、MFA 失败计数、IP 限速

## Comments

- 2026-11-04（工单 01 实现时发现）：`/auth/mfa/bind/confirm`、`/auth/mfa/recovery-codes/regenerate`、`/auth/mfa/unbind` 目前不限动态码错误次数；持有 Access Token 的攻击者可借暴力猜码重新生成 Recovery Code。本工单的「动态码失败计在 Identity 上」应覆盖这三个入口。
- 2026-11-04（工单 02 实现时发现）：`/auth/login/mfa` 在待 MFA 有效期（5 分钟）内不限提交次数，可反复猜动态码或 Recovery Code；本工单的 MFA 失败计数也应覆盖这个入口。
- 2026-11-05（工单 07 实现时留下接入点）：`PasswordService`（`io.saasforge.iam.password.service`）中标注「工单 06 在此接入」的四处需计入失败：`changeIdentityPassword` 与 `changeOperatorPassword` 的原密码错误（计在 Identity / Operator 上，返回 `password.currentInvalid`）；`verifyResetCode` 的验证码错误（按规范化后的邮箱或手机号计数，未注册目标同样计数）；`reset` 中 `mfa.verifyLoginCode` / `mfa.useRecoveryCode` 的动态码或 Recovery Code 错误（计在 Identity 上）。锁定期内这些入口也应拒绝。
- 2026-11-05（工单 04 实现时发现）：Operator 侧同样不限次数的入口：`/platform/auth/login/mfa`、`/platform/auth/login/bind/confirm`（会话内 5 分钟有效期），以及凭 Platform Access Token 的 `/platform/auth/mfa/{bind/confirm,replace/start,recovery-codes/regenerate,unbind}`；动态码失败应计在 Operator 上。
- 2026-11-05（实现时确认）：`auth.locked` 为 HTTP 429（与 `verification.tooFrequent`、网关 `request.rateLimited` 一致），剩余等待秒数放在 `Retry-After` 响应头，并作为 `userTip` 文案参数；统一信封失败时没有 `data`，未改 SDK。达到阈值的那次失败仍返回原错误码，之后才返回 `auth.locked`。First Factor（密码登录、验证码登录、忘记密码 `verify`）共用按规范化登录标识的计数；动态码、Recovery Code（本工单 Comments 列出的全部入口，含 Operator 侧）与自助改密码的原密码计在 Identity / Operator 上，被锁定的 Identity / Operator 换标识通过 First Factor 也被拒。客户端 IP 由网关新增的 `ClientIpFilter` 写入 `X-Forge-Client-Ip`（覆盖客户端自带值，按 `trusted-proxies` 还原），不用 `X-Forwarded-For`：网关会丢弃其中不可信的地址，服务的 `forward-headers-strategy: framework` 也会消费它。网关前有代理时须把代理地址配进 `trusted-proxies`，否则全部用户共用一个 IP。配置 `forge.iam.lockout.{tenant,platform}.{max-failures,window,lock-duration,ip-max-failures}`，默认 5 / 15m / 15m / 100。未新增 Flyway 迁移。详见 docs/tenant-login.md「Login Lockout」。
- 2026-11-05（工单 08 前端实现时约定）：tenant-app 期望 `auth.locked` 响应带 HTTP 头 `Retry-After: <剩余秒数>`（整数秒，信封本身没有放置剩余时间的字段），SDK 解析为 `ApiError.retryAfter`，界面按分钟向上取整展示「请 N 分钟后再试」；没有该头时退回展示 userTip。状态码建议 429。若后端选用其他字段，请同步修改 `frontend/packages/sdk/src/api.ts` 与 `frontend/apps/tenant-app/src/error-tip.ts`。
