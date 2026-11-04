# 01: Identity 的 Authenticator 与 Recovery Code

**What to build:** 按 [spec](../spec.md) 2.1–2.2 节，为 Identity 提供 TOTP 校验、Authenticator 绑定与解绑、Recovery Code 生成与重新生成；TOTP 实现须可被 Operator 复用（工单 04）。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] TOTP：SHA-1、6 位、30 秒步长、前后各 1 个步长容差；同一动态码有效期内不能重复使用
- [ ] 密钥 AES-GCM 加密入库；加密密钥经 `@ConfigurationProperties` record 绑定，不设默认值，漏配时 iam 启动失败
- [ ] 绑定两步：生成密钥返回 `otpauth://` URI；提交首个动态码通过后生效，同时返回 10 个 Recovery Code（只此一次），服务端只存哈希
- [ ] 已绑定时再验证一次动态码可重新生成 Recovery Code，旧组全部作废
- [ ] 验证一次动态码后可自助解绑
- [ ] Identity 的 Authenticator 与 Recovery Code 表（Operator 的另建表，见工单 04；Flyway 迁移，标准列、逻辑删除、`WHERE NOT deleted` 部分唯一索引）
- [ ] 审计 `mfa.bind`、`mfa.unbind`、`mfa.recoveryCodesRegenerate`
- [ ] 绑定接口同时接受 Access Token 与 iam 登录会话（为工单 03 的当场绑定预留），其余接口要求 Access Token
- [ ] 测试覆盖：时钟容差边界、防重放、Recovery Code 只返回一次、重新生成后旧码失效、漏配密钥启动失败

## Comments
