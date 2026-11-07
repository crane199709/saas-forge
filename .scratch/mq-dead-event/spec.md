# 消费端死信规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

`EventConsumer` 处理失败时返回 `ConsumeResult.FAILURE`，由 RocketMQ 重投。重试耗尽后事件离开正常消费路径，目前既无记录也无处置入口。

目标：重试耗尽的事件成为可查询、可处置的 Dead Event，并建立 Operator 运维通道（[ADR-0021](../../docs/adr/0021-operator-ops-via-service-internal-endpoints.md)）。

非目标：

- 改变 Outbox 的无限重试：发送失败多半是 MQ 整体不可用，术语表承诺事件终将送达。Outbox 只增加积压指标。
- 向 Operator 展示 payload、向 Tenant Admin 开放死信。
- 把事件重新发到 MQ，或依赖 RocketMQ 死信主题处理。

## 2. 运维通道

- 新增 SDK 模块承载 `/internal/ops/*` 的令牌校验与路由；`forge.ops.token` 无默认值，引入后漏配即启动失败。
- `forge-platform` 持有服务名到令牌的映射，经 `lb://<service>` 调用；服务列表取自 IAM 已上报 Manifest 的服务。
- 网关继续拒绝 `/internal/`。平台新增 `platform:ops:read` 与 `platform:ops:manage`，由平台校验并记录审计。
- 某服务不可达或未引入该能力时，结果中标记该服务不可用，不影响其他服务。

## 3. Dead Event 的形成

- 新增配置 `forge.mq.max-delivery-attempts`，默认 16。`getDeliveryAttempt()` 达到上限且 Handler 仍失败时形成 Dead Event。
- 业务事务已回滚，所以在**独立事务**中写入 Dead Event，再返回 `SUCCESS`。写入失败继续返回 `FAILURE`，保证事件不丢。
- 解析失败同样形成 Dead Event。没有对应 Handler 的情况维持现状（跳过并告警）。
- 消费组的实际重试次数不得小于该上限，否则事件会先进入 RocketMQ 死信主题；实现时须验证本地部署的配置。
- 按（消费组、事件 ID）唯一，重复写入幂等。
- 表 `forge_dead_event` 与 `forge_outbox` 一样不带 RLS、不用标准列，`tenant_id` 只用于恢复上下文和展示。
- 指标：`forge.mq.dead.events`（新增）、Outbox 未发送积压数。

## 4. 处置

- **重放：** 在所属服务内恢复租户上下文，走原有去重逻辑直接调用 Handler。成功后状态为已重放；失败后仍待处置，并更新错误信息。
- **放弃：** 进入终态，保留记录。
- 两种处置都要填写原因，以条件更新防止并发重复处置。审计动作：`ops.deadEventReplay`、`ops.deadEventAbandon`。
- 已处置的记录保留 90 天后清理；待处置记录不自动删除。
- 接口与界面只返回元数据和错误信息，不返回 payload。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。
