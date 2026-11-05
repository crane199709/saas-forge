# 06: Login Lockout

**What to build:** 按 [spec](../spec.md) 第 3 节，两个 Realm 的认证失败按登录标识计数并临时锁定，另按 IP 限速。

**Blocked by:** 02, 04

**Status:** ready-for-agent

- [ ] First Factor 失败按规范化登录标识计数，不论标识是否存在；动态码与 Recovery Code 失败计在对应 Identity 或 Operator 上
- [ ] 窗口内达到阈值即锁定，锁定期内拒绝该标识全部认证，返回 `auth.locked` 与剩余等待时间；到期自动解除
- [ ] 按客户端 IP 限速
- [ ] 阈值、窗口、锁定时长、IP 限速按 Realm 配置（`@ConfigurationProperties` record），参考默认 15 分钟 5 次锁 15 分钟；计数存 Redis
- [ ] 审计 `auth.locked`
- [ ] 测试覆盖：存在与不存在的标识表现一致、到期解锁、MFA 失败计数、IP 限速

## Comments

- 2026-11-04（工单 01 实现时发现）：`/auth/mfa/bind/confirm`、`/auth/mfa/recovery-codes/regenerate`、`/auth/mfa/unbind` 目前不限动态码错误次数；持有 Access Token 的攻击者可借暴力猜码重新生成 Recovery Code。本工单的「动态码失败计在 Identity 上」应覆盖这三个入口。
- 2026-11-04（工单 02 实现时发现）：`/auth/login/mfa` 在待 MFA 有效期（5 分钟）内不限提交次数，可反复猜动态码或 Recovery Code；本工单的 MFA 失败计数也应覆盖这个入口。
- 2026-11-05（工单 04 实现时发现）：Operator 侧同样不限次数的入口：`/platform/auth/login/mfa`、`/platform/auth/login/bind/confirm`（会话内 5 分钟有效期），以及凭 Platform Access Token 的 `/platform/auth/mfa/{bind/confirm,replace/start,recovery-codes/regenerate,unbind}`；动态码失败应计在 Operator 上。
