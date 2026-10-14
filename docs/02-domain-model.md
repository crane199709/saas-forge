# saas-forge 领域模型

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。

## 核心模型

核心领域收敛为：

```text
Platform
├── Tenant
├── Identity
├── Membership
├── Organization
├── Role
├── Permission
├── Plan
├── Subscription
├── Feature
├── Quota
└── Audit
```

核心关系：

```text
Platform
 ├── Plan ── Feature / Quota
 └── Tenant ── Subscription ── Plan

Identity ── Membership ── Tenant
Membership ── Role ── Permission
```

## Platform 与 Product Metadata

第一阶段中，一个 `saas-forge` 部署实例等于一个 Platform，表示当前 SaaS 产品的全局范围。平台级能力包括 Tenant、Plan、Subscription、平台管理员、平台权限、全局配置和审计。

运行中的产品可保留轻量产品元数据，例如：

```yaml
saas-forge:
  product:
    code: cloud-lis
    name: Cloud LIS
```

Product Metadata 用于产品名称展示、品牌、权限和 Feature 命名空间、平台标题及 SDK 标识；它不参与多应用管理，不要求独立数据表，不与 Tenant 建立多对多关系，也不是一级业务领域对象。

## Tenant

Tenant 是 SaaS 客户的逻辑隔离空间，其业务含义可为医院、企业、学校、门店集团、政府机构或个人工作空间，不绑定具体行业。

基础能力包括创建、修改、启用、停用、冻结、租户配置、有效期、生命周期和租户管理员。Tenant 的持久状态、派生访问结果与唯一允许状态迁移见[核心领域契约](17-core-domain-contracts.md#tenant)；实现不得推定其中未列出的迁移。

## Identity、Membership 与 Organization

Identity 表示平台中的自然人或机器身份，不能将 `User` 与 Tenant User 强耦合。一个 Identity 可加入多个 Tenant。

Membership 表示某 Identity 以成员身份加入某 Tenant；租户级 Role 应绑定 Membership，而非全局 Identity。这样同一用户可在 Tenant A 为管理员，在 Tenant B 为普通成员。

每个 Tenant 一个 Organization，其中允许多棵 OrganizationUnit 树；Membership 可关联零到多个组织单元，不设置主部门。组织归属不授予 Permission 或限定业务数据可见范围，规则见[核心领域契约](17-core-domain-contracts.md#organization-与-membership-组织归属)。

## 访问控制模型

首期采用 RBAC：

```text
Membership → Role → Permission
```

平台权限与租户权限必须隔离，分别使用受控权限目录；租户角色可拥有 `system:user:create`、`system:role:update`。业务系统可注册如 `lis:specimen:list`、`lis:report:audit` 的权限，平台只负责定义、注册、绑定、授权和校验。Tenant 管理员管理已有 Permission 的分配，不自行定义或修改权限代码。

Permission 统一命名为 `namespace:resource:action`，例如 `system:user:list`、`lis:report:audit`、`crm:customer:create`；官方 Example 使用 `example:project:create` 等具体代码。`system` 是内置命名空间，业务系统在受控注册确定的命名空间内维护自己的 Permission。第 4 阶段边界见 [ADR 0056](adr/0056-tenant-organization-and-permission-boundaries.md)。

## 商业权益模型

- **Plan**：产品对客户销售或授权的套餐，关联 Feature、Quota、套餐配置和状态。
- **Subscription**：Tenant 当前订阅 Plan 的关系及生命周期，包含状态、生效/到期时间、试用期、自动续订、取消时间、暂停状态和套餐快照。到期由 `endsAt` 在权益判断时派生，不持久化为 `EXPIRED`；MVP 取消即时停止权益，不支持期末取消；其余生命周期规则见[核心领域契约](17-core-domain-contracts.md#subscription)。首期只覆盖产品权益与生命周期，不等同于完整计费系统。
- **Feature**：可由套餐控制的产品能力。平台管理其定义、状态、Plan 关系、Tenant 实际权益及校验。
- **Quota**：资源使用额度，模型需考虑 `Quota Definition`、`Quota Limit`、`Quota Usage` 与 `Quota Operation`；SDK 提供 `check`、`consume`、`release`、`usage`。MVP 的计量范围、一致性、重试幂等和失败补偿以[核心领域契约](17-core-domain-contracts.md#quota)为准。

Permission 回答“当前用户能否执行操作”，Feature 回答“当前 Tenant 是否购买或启用产品能力”。一次业务请求可能同时需要 Feature Check 与 Permission Check。

## Audit

Audit 覆盖平台操作、租户管理、登录、权限、套餐订阅，以及业务系统上报的关键操作。记录建议包含 Tenant、Identity、Membership、Action、Resource、Request ID、IP、User Agent、Timestamp、Result 和 Metadata。

业务系统可使用：

```java
audit.log("lis.report.publish", reportId);
```

未来也可提供 `@Audit("lis.report.publish")` 注解。
