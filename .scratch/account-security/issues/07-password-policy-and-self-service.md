# 07: Password Policy、自助改密码与忘记密码

**What to build:** 按 [spec](../spec.md) 第 4–5 节，按 Realm 执行 Password Policy，提供 Identity 与 Operator 自助改密码、Identity 忘记密码。

**Blocked by:** 02

**Status:** resolved

- [x] Password Policy：长度 8–72（下限可配）、内置约 1 万条弱密码清单（随 iam 资源打包、启动时加载）、拒绝与登录标识相同；不强制字符组合与定期修改
- [x] 在注册、自助改密码、忘记密码重设、管理员重置 Operator 密码四处统一执行；违反返回 `password.*` 错误码
- [x] Identity 与 Operator 自助改密码（需原密码）
- [x] Identity 忘记密码：`VerificationCodeService` 新增用途发码，未注册目标响应一致；已绑定 Authenticator 时还须完成 MFA
- [x] 改密码或重设后吊销该账号其他会话与 Refresh Token，并使已签发 Access Token 失效：Operator 沿用管理员重置的机制，Identity 侧机制在本工单确认并记录
- [x] 上述入口的失败计入 Login Lockout（工单 06 完成后接入）
- [x] 审计 `password.change`、`password.reset`
- [x] 测试覆盖：四个入口一致校验、弱密码被拒、存量密码仍可登录、改密码后旧 Refresh Token 失效、忘记密码在已绑定时要求 MFA

## Comments

- 2026-11-05（实现时确认）：Identity 侧失效机制——Identity 没有自己的版本，改密码或重设后递增其全部有效 Membership 的版本，网关据此拒绝已签发的 Access Token；`RedisAuthorizationService` 新增按主体的授权索引，删除该账号全部授权（自助改密码保留发起请求的 Access Token 所属授权，刷新后按新版本继续使用）；`LoginSessionRegistry` 在登录成功时登记会话标识，改密码后终止全部 iam 登录会话。Operator 沿用递增 Operator 版本，并同样吊销其他授权与登录会话。详见 docs/tenant-login.md「密码」。
- 2026-11-05：错误码沿用项目驼峰风格（`password.tooShort`、`password.tooCommon`、`password.sameAsIdentifier`、`password.tooLong`、`password.currentInvalid`、`password.resetExpired`、`password.mfaRequired`）。弱密码清单取自 zxcvbn（MIT）passwords.txt 频率表中长度不少于 8 的前 10000 条。接受邀请为待激活身份设密码等同注册，一并执行 Password Policy；管理员创建 Operator 的初始密码未接入（不在规格列出的四个入口内）。
- 2026-11-05：「计入 Login Lockout」留待工单 06，接入点见工单 06 的 Comments。
- 2026-11-06（code-review 修复）：`reset` 的 MFA 步骤写 `auth.mfa` 审计（`detail.method` 区分 `totp` 与 `recoveryCode`，失败带错误码）；缺少因素时的 `password.mfaRequired` 没有提交任何因素，不记。忘记密码路径无认证上下文，`identity.updated_by` 写 NULL（`IdentityMapper.changePassword` 新增 `actor`），自助改密码仍为本人。`ResetTicket` 保留原名：它是服务层结果兼接口响应，与 `CodeDelivery`（`verification.model`）同类，符合现有先例。
