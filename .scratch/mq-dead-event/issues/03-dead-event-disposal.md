# 03: 重放、放弃与清理

**What to build:** 按 spec 第 4 节，提供 SDK 运维接口与平台接口，并按保留期清理已处置记录。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 重放恢复租户上下文，成功后事件只生效一次；失败时保持待处置并更新错误
- [x] 重放不向 MQ 发消息，其他消费组不会再次收到
- [x] 放弃必须填写原因，已处置事件不能再次处置，并发处置只有一次成功
- [x] 平台接口记录 `ops.deadEventReplay`、`ops.deadEventAbandon`
- [x] 接口响应不含 payload
- [x] 已处置 90 天后清理，待处置记录不被清理

## Answer

提供 SDK 内部及平台的分页查询、详情、重放与放弃接口，所有响应仅含元数据；重放恢复原租户、复用消费去重并与终态同事务提交，失败独立更新错误。已处置事件每日按 90 天保留期清理，租户最终删除通过 provisioning 的适配清理其死信。

## Comments

### 2026-11-07 实现与验证

- 按已确认的后端集成/HTTP seam 做纵向红绿切片：查询 404、重放 404、Handler 失败 500、放弃 404、缺少保留期 API、平台路由 404 和缺少租户删除 Handler 均先观察失败，再实现通过。
- `EventConsumer` 在关闭 MQ 时保留服务内处理能力但不启动订阅；重放只调用原 `handle`，保留原消费组、事件 ID 与 `original_tenant_id`。先恢复租户，再开启独立处置事务，条件更新取得行锁、去重、Handler 与终态一起提交；失败退出回滚后独立条件刷新最近错误。
- SDK `GET /internal/ops/dead-events`（默认 status=PENDING/page=1/size=20，每页上限100）、`GET /{id}`、`POST /{id}/replay`、`POST /{id}/abandon`。POST 请求体 `{ "reason": "..." }`，原因去除首尾空白后必填；成功200返回元数据，无 payload 或原租户头。
- 平台路径为 `/ops/services/{service}/dead-events`，网关外部加 `/platform`。查询要求 read，处置要求 manage；成功和失败手动记录 `ops.deadEventReplay` / `ops.deadEventAbandon`，包含 Operator、服务、死信 ID、原因及成功时原事件 ID。下游业务拒绝转换为平台领域错误，避免失败误记为成功。
- 外部命令错误：`ops.reasonRequired` 400、`ops.deadEventNotFound` 404、`ops.deadEventAlreadyHandled` / `ops.deadEventReplayFailed` 409、`ops.serviceUnavailable` 503。内部处置沿用 `deadEvent.*`。
- 每次启动后及每隔一天以 `status IN ('REPLAYED','ABANDONED') AND resolved_at < cutoff` 清理，只删处置超过90天的终态。SDK 自有维护线程不增加 scheduling/Redis 依赖，多实例幂等删除，不覆盖平台生命周期调度器。
- provisioning 上层注册 MQ 的 `TenantDataCleaner`，显式按 tenant_id 清理基础设施记录，MQ 不反向依赖 provisioning。租户删除死信重放时可能清理自身，响应使用同事务取得的成功元数据快照，避免业务已提交后误报404。
- 执行 `./mvnw -o -pl forge-platform -am install -DskipTests` 刷新本地依赖。最终目标验证通过：MQ `DeadEventDisposalTest,DeadEventCaptureTest,ArchitectureTest` 19项；provisioning `ArchitectureTest` 1项；platform `DeadEventOpsTest,OpsChannelTest,ArchitectureTest` 11项。覆盖真实非超级用户 PostgreSQL RLS、并发重放/放弃、失败回滚/错误刷新、无 Handler/非法JSON/非法租户头、保留期、租户清理隔离和幂等、平台权限/Realm拒绝与成功失败审计。
- MQ PostgreSQL 测试已改为模块共享容器、各测试类独立库；平台继承共享集成基类且使用唯一ID筛选审计，不假设数据库只有本类数据。Spotless 与 `git diff --check` 通过。接入指南、SDK包说明、配置说明和 CHANGELOG 同步更新；本票不改表结构，01/02的迁移及模板继续适用。模块全量由集成分支交付前统一验证，本票不新增前端。
