# 05: MFA Reset 与身份查询

**What to build:** 按 [spec](../spec.md) 2.5 节，平台可按登录标识精确查找 Identity 并重置其 MFA；Super Admin 可重置 Operator 的 MFA。

**Blocked by:** 01, 04

**Status:** resolved

- [x] 新增平台权限码 `platform:identity-mfa:reset`
- [x] 身份查询接口：按账号、手机号或邮箱精确查找，只返回 Identity 标识、展示名、是否已绑定；不分页浏览、不返回 Membership
- [x] 重置 Identity MFA：清除 Authenticator 与全部 Recovery Code
- [x] 重置 Operator MFA：仅 Super Admin
- [x] 审计 `mfa.reset`（操作人为 Operator，资源为 Identity 或 Operator）
- [x] 租户侧不存在任何重置他人 MFA 的接口
- [x] 测试覆盖：无权限 403、查不到时的响应、重置后下次登录不再要求 MFA

## Comments
- 2026-11-05（工单 04 实现时记录）：Operator 的 Authenticator 与 Recovery Code 存于 `operator_authenticator`、`operator_recovery_code`（V22），由 `mfa.service.OperatorMfaService` 管理，Mapper 已有 `deleteByOperator`；重置时可一并删除 Redis 中进行中的绑定 `iam:global:mfa:binding:operator:<id>`。`mfa-required=true` 时被重置的 Operator 下次登录进入「必须绑定」，无需额外处理。
- 2026-11-05（工单 05 实现时记录）：接口均在 Platform Realm、只接受 Platform Access Token。`POST /iam/platform/identities/lookup` 请求体 `{identifier}`（账号、邮箱不区分大小写、手机号，精确匹配），返回 `{identityId, displayName, mfaBound}`，查不到 404 `mfa.identityNotFound`；`POST /iam/platform/identities/{id}/mfa/reset` 需 `platform:identity-mfa:reset`；`POST /iam/platform/operators/{id}/mfa/reset` 仅超级管理员。未绑定时重置返回 409 `mfa.notBound`，无权限 403 `auth.forbidden`。未新增 Flyway 迁移（权限码随平台清单 v3 启动登记）。
- 2026-11-06（code-review 修复）：`messages.properties` 补 `mfa.identityNotFound`，平台页面不再需要前端兜底文案。`IdentityMfaStatus` 保留原名：服务层结果兼接口响应，与 `CodeDelivery` 同类，符合现有先例。
- 2026-11-06（用户确认）：MFA Reset 同解绑一样调用 `MfaCompletionService.revokeCompletions`：清除现存授权的 MFA 标记并终止登录会话，Identity 与 Operator 都适用；Operator 在 `mfa-required=true` 时旧 Refresh Token 因此不能再刷新。已签发的 Access Token 不受影响，到期为止。规格 2.5 已同步。
