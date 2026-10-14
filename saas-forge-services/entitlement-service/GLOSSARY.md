# Entitlement Context

Entitlement 管理 Tenant 获得的产品能力、订阅版本和配额真相；它不拥有 Tenant 生命周期、Identity 或 Permission。

## Language

**Subscription**:
Tenant 在一个有效期内获得指定 Plan 权益的关系；到期由 `endsAt` 在权益判断时派生。
_Avoid_: Contract, billing account

**Trial Eligibility**:
Tenant 获得一次试用 Subscription 的资格；套餐变更不会重置已开始的试用期限。
_Avoid_: Repeated trial, trial reset

**Subscription Version**:
一次 Subscription 生命周期的不可变权益记录；套餐变更产生新版本并将旧版本标记为 `SUPERSEDED`。
_Avoid_: In-place plan update

**Plan**:
产品向 Tenant 提供的 Feature 与 Quota 组合；它是新 Subscription 权益快照的来源，不回溯改变既有 Subscription。
_Avoid_: Subscription, application plan

**Feature**:
可由 Plan 授予的产品能力；禁用的 Feature 是全局运行时开关，会覆盖 Subscription 快照中的同名权益。
_Avoid_: Permission, feature flag

**Quota Definition**:
可由 Plan 配置的额度类型定义；它不同于 Tenant 已用量和单次计量操作。
_Avoid_: Usage, quota counter

**Quota Usage**:
Tenant 对一个 Quota Definition 的当前已用量，是额度判定的权威累计值。
_Avoid_: Quota definition, operation log

**Quota Operation**:
一次带 `operationId` 的额度扣减或释放请求，用于保证计量重试幂等。
_Avoid_: Usage, quota definition
