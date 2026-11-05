# 05: MFA Reset 与身份查询

**What to build:** 按 [spec](../spec.md) 2.5 节，平台可按登录标识精确查找 Identity 并重置其 MFA；Super Admin 可重置 Operator 的 MFA。

**Blocked by:** 01, 04

**Status:** ready-for-agent

- [ ] 新增平台权限码 `platform:identity-mfa:reset`
- [ ] 身份查询接口：按账号、手机号或邮箱精确查找，只返回 Identity 标识、展示名、是否已绑定；不分页浏览、不返回 Membership
- [ ] 重置 Identity MFA：清除 Authenticator 与全部 Recovery Code
- [ ] 重置 Operator MFA：仅 Super Admin
- [ ] 审计 `mfa.reset`（操作人为 Operator，资源为 Identity 或 Operator）
- [ ] 租户侧不存在任何重置他人 MFA 的接口
- [ ] 测试覆盖：无权限 403、查不到时的响应、重置后下次登录不再要求 MFA

## Comments
- 2026-11-05（工单 04 实现时记录）：Operator 的 Authenticator 与 Recovery Code 存于 `operator_authenticator`、`operator_recovery_code`（V21），由 `mfa.service.OperatorMfaService` 管理，Mapper 已有 `deleteByOperator`；重置时可一并删除 Redis 中进行中的绑定 `iam:global:mfa:binding:operator:<id>`。`mfa-required=true` 时被重置的 Operator 下次登录进入「必须绑定」，无需额外处理。
