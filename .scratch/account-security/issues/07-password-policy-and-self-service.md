# 07: Password Policy、自助改密码与忘记密码

**What to build:** 按 [spec](../spec.md) 第 4–5 节，按 Realm 执行 Password Policy，提供 Identity 与 Operator 自助改密码、Identity 忘记密码。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] Password Policy：长度 8–72（下限可配）、内置约 1 万条弱密码清单（随 iam 资源打包、启动时加载）、拒绝与登录标识相同；不强制字符组合与定期修改
- [ ] 在注册、自助改密码、忘记密码重设、管理员重置 Operator 密码四处统一执行；违反返回 `password.*` 错误码
- [ ] Identity 与 Operator 自助改密码（需原密码）
- [ ] Identity 忘记密码：`VerificationCodeService` 新增用途发码，未注册目标响应一致；已绑定 Authenticator 时还须完成 MFA
- [ ] 改密码或重设后吊销该账号其他会话与 Refresh Token，并使已签发 Access Token 失效：Operator 沿用管理员重置的机制，Identity 侧机制在本工单确认并记录
- [ ] 上述入口的失败计入 Login Lockout（工单 06 完成后接入）
- [ ] 审计 `password.change`、`password.reset`
- [ ] 测试覆盖：四个入口一致校验、弱密码被拒、存量密码仍可登录、改密码后旧 Refresh Token 失效、忘记密码在已绑定时要求 MFA

## Comments
