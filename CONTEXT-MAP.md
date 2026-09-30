# SaaS Forge Context Map

saas-forge 将身份与访问、Tenant 访问、权益、审计、浏览器交付和共享契约划分为六个上下文。每个领域术语只由一个上下文定义；其他上下文通过本 Map 引用，不复制定义。

## Contexts

- [IAM](saas-forge-services/iam-service/CONTEXT.md)：拥有 Identity、Credential、用户与服务 Token、会话、Signing Key、OAuth Client 和 Platform Role。
- [Tenant Access](saas-forge-services/tenant-access-service/CONTEXT.md)：拥有 Tenant、Membership、Tenant Context、Tenant Operation Target、Tenant 生命周期、Tenant Context Switch 和 Invitation。
- [Entitlement](saas-forge-services/entitlement-service/CONTEXT.md)：拥有 Plan、Subscription、Feature 和 Quota。
- [Audit](saas-forge-services/audit-service/CONTEXT.md)：拥有只追加 Audit Record。
- [Gateway](gateway/CONTEXT.md)：拥有受控浏览器 Origin、Cookie、CSRF 与浏览器交付边界，不拥有下游领域事实。
- [Contracts](saas-forge-contracts/CONTEXT.md)：拥有 Committed Fact Event 与 v1 Contract Baseline 等 Published Language 治理，不拥有各服务领域事实。

## Example 业务上下文

- [Project / Task Example](examples/CONTEXT.md)：代表开发者接入底座的业务应用，拥有租户隔离的 Project 业务记录，不属于上述六个底座上下文。当前已实现 Project 创建、列表、详情与修改；Project 删除和 Task API 尚未实现。

## Relationships

```text
External Client
      │
      ▼
   Gateway ─────► IAM
      ├─────────► Tenant Access
      └─────────► Entitlement

IAM ◄──────────► Tenant Access
 ▲                  │  ▲
 │                  ▼  │
 └──────────── Entitlement

IAM ───────────────┐
Tenant Access ─────┼──► Audit
Entitlement ───────┘

Contracts - - Published Language - -► Gateway / IAM / Tenant Access / Entitlement / Audit
```

- **Contracts → 全部其他上下文**：提供版本化 OpenAPI、Protobuf、事件、Redis 与日志 Published Language；不拥有各服务领域事实。
- **Gateway → IAM / Tenant Access / Entitlement**：只按正式 OpenAPI 暴露并转发公开 REST operation；当前没有正式 Audit 公网路由。
- **IAM ↔ Tenant Access**：IAM 向 Tenant Access 验证 Membership；Tenant Access 向 IAM 执行 Identity、Password Setup、Platform Role 与 Session Revocation 协作。
- **Tenant Access ↔ Entitlement**：Tenant Access 编排 Tenant 初始化及其 Quota 副作用；Entitlement 向 Tenant Access 校验 Tenant 权威状态。
- **Entitlement → IAM**：Entitlement 通过版本化同步契约复核 Platform Role。
- **IAM / Tenant Access / Entitlement → Audit**：只通过 Committed Fact Event 单向提供来源事实；Audit 不反向裁决来源事务是否成功。
- **IAM → Gateway 与 Token 接收端**：IAM 是 Token、JWKS 与 Revocation 权威；验证方在权威状态不可判定时失败关闭。

全部关系都通过版本化契约协作；上下文之间不存在 Shared Kernel、共享领域实体、共享数据库表或共享迁移。
