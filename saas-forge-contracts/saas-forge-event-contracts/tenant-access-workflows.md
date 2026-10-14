# Tenant Access 跨服务工作流事件

本文件定义[跨服务工作流契约](../../docs/18-tenant-access-cross-service-workflows.md)涉及的首批 CloudEvents JSON 类型。所有事件由其拥有数据的服务在本地事务提交后通过 Outbox 发布；Kafka 交付至少一次，消费者必须以 CloudEvents `id`（本文称事件 ID）幂等。全部事件必须符合[统一 CloudEvents JSON 信封](cloudevents-envelope.v1.schema.json)及本目录的[版本规则](README.md#版本规则)。

## 共同约束

每个事件使用版本化 `type`、唯一 CloudEvents `id`、CloudEvents `source`、CloudEvents `time` 和可选 `traceId` 扩展。`data` 仅可包含下表列出的标识符、状态和时间字段。密码、邮箱原文、Invitation 激活令牌、Access/Refresh Token、Client Secret 和 Cookie 不得进入事件。

| 类型 | 生产者 | `data` 白名单 |
|---|---|---|
| `com.saas.forge.iam.platform-admin-initialized.v1` | IAM | `identityId`、`roleKey`、`initialCredentialExpiresAt` |
| `com.saas.forge.iam.platform-admin-initial-credential-reset.v1` | IAM | `identityId`、`resetRequestId`、`initialCredentialExpiresAt` |
| `com.saas.forge.iam.password-setup-delivered.v1` | IAM | `identityId`、`deliveryRequestId`、`challengeExpiresAt` |
| `com.saas.forge.iam.password-established.v1` | IAM | `identityId`、`credentialId` |
| `com.saas.forge.tenant.created.v1` | Tenant Access | `tenantId`、`status`、`actorIdentityId` |
| `com.saas.forge.brand.asset-uploaded.v1` | Tenant Access | `tenantId`、`assetId`、`actorIdentityId`、`kind`；不包含图像内容或对象存储凭据 |
| `com.saas.forge.tenant.administrator-initialized.v1` | Tenant Access | `tenantId`、`membershipId`、`identityId`、`roleId`、`status`、`actorIdentityId` |
| `com.saas.forge.quota-definition.created.v1` | Entitlement | `quotaDefinitionId`、`code`、`status`、`actorIdentityId` |
| `com.saas.forge.quota-definition.activated.v1` | Entitlement | `quotaDefinitionId`、`code`、`status`、`actorIdentityId` |
| `com.saas.forge.plan.created.v1` | Entitlement | `planId`、`code`、`status`、`actorIdentityId` |
| `com.saas.forge.plan.activated.v1` | Entitlement | `planId`、`code`、`status`、`actorIdentityId` |
| `com.saas.forge.subscription.created.v1` | Entitlement | `subscriptionId`、`tenantId`、`planId`、`status`、`endsAt`、`actorIdentityId` |
| `com.saas.forge.invitation.accepted.v1` | Tenant Access | `invitationId`、`tenantId`、`membershipId`、`identityId`、`actorIdentityId` |
| `com.saas.forge.invitation.created.v1` | Tenant Access | `invitationId`、`tenantId`、`actorIdentityId`、`expiresAt`；不含邮箱或令牌 |
| `com.saas.forge.invitation.resent.v1` | Tenant Access | `invitationId`、`tenantId`、`actorIdentityId`、`expiresAt`；仅代表链接已轮换，不代表 SMTP 成功 |
| `com.saas.forge.invitation.revoked.v1` | Tenant Access | `invitationId`、`tenantId`、`actorIdentityId`、`expiresAt` |
| `com.saas.forge.iam.tenant-context-switched.v1` | IAM | `identityId`、`previousMembershipId`、`membershipId`、`tenantId` |
| `com.saas.forge.membership.disabled.v1` | Tenant Access | `membershipId`、`tenantId`、`identityId`、`actorIdentityId`、`quotaReleasePending` |
| `com.saas.forge.tenant.suspended.v1` | Tenant Access | `tenantId`、`actorIdentityId`、`revokedSessionCount` |
| `com.saas.forge.tenant.closed.v1` | Tenant Access | `tenantId`、`actorIdentityId`、`revokedSessionCount` |
| `com.saas.forge.tenant.details-updated.v1` | Tenant Access | `tenantId`、`actorIdentityId`、管理名称和可空有效期 |
| `com.saas.forge.iam.sessions-revoked.v1` | IAM | `revocationRequestId`、`scope`、`membershipId` 或 `tenantId`、`revokedSessionCount` |
| `com.saas.forge.quota.consumed.v1` | Entitlement | `tenantId`、`quotaDefinitionId`、`operationId`、`amount`、`purpose` |
| `com.saas.forge.quota.released.v1` | Entitlement | `tenantId`、`quotaDefinitionId`、`operationId`、`amount`、`purpose` |

`com.saas.forge.iam.sessions-revoked.v1` 是 IAM 已完成安全撤销的事实，不承诺对应 Membership 禁用或 Tenant 冻结也已提交。需要审计完整业务结果的消费者必须分别消费 Tenant Access 的领域事件，不能从会话撤销事件推导领域状态。

`revokedSessionCount` 只统计该 `revocationRequestId` 首次从可用变为已撤销的 Refresh Token Family；已撤销、已过期、重复批次或幂等重放不增加计数。事件在全部批次完成后只发布一次，不增加 `jti` 数量或 Token 明细字段。

Password Setup 事件不得包含邮箱、Challenge Token 或摘要、密码、链接、邮件正文或 SMTP 响应。`com.saas.forge.iam.password-setup-delivered.v1` 只表示 SMTP 已明确接受最新有效 Challenge 对应的邮件；超时后可能迟到但已被替换的旧邮件不得发布该事实。Platform Admin bootstrap 没有用户操作者，不得伪造 `actorIdentityId`。
