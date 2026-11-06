# 03: Tenant 的 MFA Requirement

**What to build:** 按 [spec](../spec.md) 2.4 节，Tenant Admin 可开启 MFA Requirement；签发进入该 Tenant 的 Token 时检查授权是否完成 MFA，未完成则拒绝，并支持当场绑定后重试。

**Blocked by:** 02

**Status:** resolved

- [x] 新增权限码 `iam:security:manage`（Tenant Admin 预置拥有），不受 Plan 限制
- [x] 租户安全设置读写接口，默认关闭；审计 `tenantSecurity.update`
- [x] exchange、switch、refresh 在目标 Tenant 有要求且授权未完成 MFA 时拒绝，错误码 `auth.mfa_required`
- [x] 开启后存量未绑定成员在下一次刷新时被拦，不做即时吊销
- [x] 当场绑定（登录会话或 Access Token）成功后，该会话与当前授权视为已完成 MFA，可重试进入
- [x] 测试覆盖：三个签发路径都被拦、绑定后可进入、无要求的 Tenant 不受影响、无权限访问设置返回 403

## Comments

- 2026-11-04（工单 02 实现时记录）：授权是否完成 MFA 记在会话主体的 `MFA` 权限上，授权码流程把该主体存入授权，Token 定制器中用 `MfaAuthority.completed(context.getPrincipal())` 读取，exchange、refresh、switch 都一样。当场绑定后要把「当前授权视为已完成 MFA」，需要同时更新登录会话的主体与授权中保存的主体（`Principal.class.getName()` 属性）。
- 2026-11-05（工单 03 实现时记录）：错误码按编码规范（原因 lowerCamelCase）实现为 `auth.mfaRequired`（403），复用 `SessionErrorCode.MFA_REQUIRED`，规格与本工单中的 `auth.mfa_required` 即指此码。要求存于 iam `tenant.mfa_required`（V21）；设置接口 `GET/PUT /security-settings`（Access Token，`{"mfaRequired": bool}`）。当场绑定：凭 Access Token 绑定时把该 Token 所属授权补记 MFA，被拒的 refresh/switch 用原刷新凭证直接重试（被拒的刷新凭证不会被消费）；凭登录会话绑定时会话主体补记 MFA 并换发会话标识，须重新走授权码流程再 exchange（被拒的授权码对应的授权不会被补记）。
- 2026-11-06（code-review 修复）：解绑时清除该 Identity 现存授权的 MFA 标记（`MfaCompletionService.revokeCompletions`），并终止其全部登录会话：会话带 MFA 标记，能走授权码流程换到新的已完成授权，且 Spring Session 不便逐个改写其他会话。授权正在换码或刷新、取不到锁时直接删除该授权（fail closed），不能跳过。绑定确认凭登录会话时响应新增 `clientId`、`redirectUri`，页面刷新后内存中的授权参数已丢，重走授权码流程要用；凭 Access Token 时这两个字段为 null。`LoginSessionRegistry` 从 `session.service` 移到 `security.service`：mfa 要登记换发后的会话标识，而 session 已依赖 mfa，留在原处会成环。MFA Reset 未同步清标记，规格 2.5 没有这项要求，留待决定。
