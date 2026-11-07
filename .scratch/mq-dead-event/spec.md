# 消费端死信规格

Status: resolved

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

## Comments

- 2026-11-07：规格已在 `codex/mq-dead-event` 集成分支实现，01—04 票均已 resolved。包含独立令牌运维通道、消费端死信捕获与指标、服务内重放/放弃、保留期与租户清理，以及平台控制台。
- 后端相关模块完整 `verify` 共 353 项通过，`archetype-it` 生成服务另有 15 项通过。临时 RocketMQ 5.3.2 实跑初始化脚本，三个消费组的 `retryMaxTimes` 均为 16。
- 规范审查发现的 3 项和规格审查发现的 1 项均已修复，复核后两轴剩余问题均为 0。捕获及重放使用安全异常类型摘要，完整异常只留服务日志；新增 6 个载荷泄露回归场景先红后绿。修复后捕获、处置与 Ops HTTP 定向测试共 28 项通过，相关 Spotless 与差异检查通过。
- 平台控制台全量单测 65 项、平台构建、全部前端类型检查及本次前端格式检查通过。Chrome Playwright HTTP mock 流程 2 项通过，覆盖查看、重放失败重试、放弃、服务/状态分页、服务故障恢复和权限；页面与控制台无非预期错误。
- 浏览器验证覆盖 1280×900 与 390×844。390px 的筛选、详情和处置可操作；列表仍受现有 220px 固定侧栏挤压，本次未扩改共享 Shell。
- 本地启动须按[业务服务接入指南](../../docs/business-service-guide.md)显式配置独立运维令牌。01—03 实现工作树已归档；04 因 Codex 当前主工作树保护保留，并同步最终集成提交。
