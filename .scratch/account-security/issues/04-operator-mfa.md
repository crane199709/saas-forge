# 04: Platform Realm 的 MFA

**What to build:** 按 [spec](../spec.md) 2.2–2.3 节，Operator 支持 Authenticator 与两步登录；`forge.iam.platform.mfa-required=true` 时未绑定者在登录中当场绑定，替换现有「开启即拒绝全部登录」的逻辑。

**Blocked by:** 01

**Status:** resolved

- [x] Operator 的 Authenticator 与 Recovery Code 单独存储，复用工单 01 的 TOTP 与加密实现
- [x] `/platform/auth/login` 对已绑定者进入待 MFA 状态；MFA 提交接口同 Tenant Realm 语义
- [x] `mfa-required=true` 且未绑定：密码通过后进入「必须绑定」状态，完成绑定与首个动态码即认证成功；默认值保持 `false`
- [x] 删除 `OperatorSessionService` 中「要求 MFA 即拒绝」的逻辑与相应错误码的旧语义
- [x] `mfa-required=true` 时不能解绑，只能更换（先验证旧动态码再绑定新的）
- [x] 审计 `auth.mfa`、`mfa.bind`、`mfa.unbind`、`mfa.recoveryCodesRegenerate`
- [x] 测试覆盖：未开启时行为不变、强制绑定流程、强制时解绑被拒、更换流程

## Comments
