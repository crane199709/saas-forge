# 03: Tenant 的 MFA Requirement

**What to build:** 按 [spec](../spec.md) 2.4 节，Tenant Admin 可开启 MFA Requirement；签发进入该 Tenant 的 Token 时检查授权是否完成 MFA，未完成则拒绝，并支持当场绑定后重试。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 新增权限码 `iam:security:manage`（Tenant Admin 预置拥有），不受 Plan 限制
- [ ] 租户安全设置读写接口，默认关闭；审计 `tenantSecurity.update`
- [ ] exchange、switch、refresh 在目标 Tenant 有要求且授权未完成 MFA 时拒绝，错误码 `auth.mfa_required`
- [ ] 开启后存量未绑定成员在下一次刷新时被拦，不做即时吊销
- [ ] 当场绑定（登录会话或 Access Token）成功后，该会话与当前授权视为已完成 MFA，可重试进入
- [ ] 测试覆盖：三个签发路径都被拦、绑定后可进入、无要求的 Tenant 不受影响、无权限访问设置返回 403

## Comments

- 2026-11-04（工单 02 实现时记录）：授权是否完成 MFA 记在会话主体的 `MFA` 权限上，授权码流程把该主体存入授权，Token 定制器中用 `MfaAuthority.completed(context.getPrincipal())` 读取，exchange、refresh、switch 都一样。当场绑定后要把「当前授权视为已完成 MFA」，需要同时更新登录会话的主体与授权中保存的主体（`Principal.class.getName()` 属性）。
- 2026-11-05（工单 04 实现时记录）：旧的 `SessionErrorCode.MFA_REQUIRED`（`auth.mfaRequired`，「Operator MFA 未实现即拒绝登录」）连同 messages.properties 中的文案已删除；本工单的 `auth.mfa_required` 请按新语义重新定义（命名沿用驼峰 `auth.mfaRequired` 与否在本工单确定）。
