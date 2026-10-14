# Invitation 激活使用同步补偿 Saga

> 2026-10-14 修订：密码建立提前到最终接受邀请之前，见 [ADR 0059](0059-invitation-acceptance-follows-password-setup-and-login.md)；同步补偿 Saga 与仅补偿 Quota 的边界继续有效。

Tenant Access 拥有 Invitation 状态迁移。受邀者先完成密码建立并以匹配 Identity 登录；最终接受时，Tenant Access 验证 Invitation、所属 Tenant 及当前凭据/会话身份，锁定待处理 Invitation，为一次激活尝试分配稳定的 `consume` 与 `release` UUIDv7，执行 `max_users` 扣减，再以本地事务创建启用 Membership 并接受 Invitation。扣减后的失败以该尝试的 `release` UUIDv7 持久化补偿，Invitation 保持 `PENDING`。Identity 与既有凭据不作为补偿对象。同一未完成尝试的重试复用这对 ID；补偿成功后重新激活会生成新的尝试与新的 ID，确保再次占用额度。若补偿尚未完成，拒绝新的激活尝试并返回 `503` / `INVITATION_ACTIVATION_COMPENSATING` 与 `Retry-After`。此模式在不使用分布式事务的前提下，保留即时激活结论和可重试计量。

本文描述的 Invitation 激活流程尚未实现：仓库中不存在 `invitations` 表、`POST /api/v1/tenant/invitation-activations` 与 `INVITATION_ACTIVATION_COMPENSATING` 错误码，该能力列在 [MVP 开发计划](../16-mvp-development-plan.md) 第 4 阶段。当前已实现的同类流程是初始 Tenant 管理员初始化，它复用同样的 `consume`/`release` 补偿模式，补偿中的错误码为 `TENANT_ADMIN_INITIALIZATION_COMPENSATING`。
