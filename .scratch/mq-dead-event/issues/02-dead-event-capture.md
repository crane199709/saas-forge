# 02: Dead Event 落库与指标

**What to build:** 按 spec 第 3 节，让重试耗尽的事件写入 `forge_dead_event`，并增加指标。

**Blocked by:** —

**Status:** resolved

- [x] 达到上限前继续返回 `FAILURE`，达到上限后独立事务写入并返回 `SUCCESS`
- [x] Dead Event 写入失败时返回 `FAILURE`，事件不丢
- [x] 重复写入同一事件幂等，解析失败也能进入死信
- [x] 验证本地部署的消费组重试次数不小于上限，必要时调整 `deploy/rocketmq`
- [x] 增加 Dead Event 与 Outbox 积压指标
- [x] 迁移覆盖 iam、platform、demo-service 与脚手架

## Answer

实现重试耗尽的 Dead Event 捕获、独立事务提交、重复写入幂等、原始消息与租户头保存；增加新增死信 Counter 与 Outbox 未发送 Gauge，并同步 SDK schema、三个服务迁移、脚手架和接入文档。

## Comments

- 2026-11-07：按已确认的消费入口/集成接口 seam 做红绿切片。首个红灯是第 16 次失败仍返回 FAILURE；实现后 SUCCESS 且业务写入回滚、死信提交。另以缺少 Counter/Gauge 的红灯分别驱动指标实现。
- `./mvnw -o -pl forge-sdk/forge-sdk-mq -am install -DskipTests` 后，`test -Dtest=DeadEventCaptureTest,ArchitectureTest` 通过 12 项（11 项捕获/配置/指标集成测试及 1 项架构检查）。覆盖捕获提交失败不 ACK、不计数、非法 JSON 原字节保存、独立提交、消费与重放共享去重、上下文恢复及无 Handler 分支。
- `./mvnw -o -pl forge-iam,forge-platform,examples/demo-service -am install -DskipTests` 后，三模块 `test -Dtest=SchemaCommentTest` 各通过 1 项，验证真实迁移与全部中文注释。SDK 与 iam V23、platform V7、demo V13、archetype V8 的 Dead Event DDL 逐字一致。
- 本机没有运行本仓库的 RocketMQ，使用隔离临时 `apache/rocketmq:5.3.2` 容器执行本地 `broker.conf` 与初始化脚本；回读 forge-iam、forge-platform、demo-service 的 `retryMaxTimes` 均为 16。脚本拒绝 15 次重试/16 次 SDK 上限与非法数字；`sh -n`、`docker compose config --quiet` 均通过，临时容器已清理。
- 已核对 [RocketMQ 5.3.2 SubscriptionGroupConfig](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/remoting/src/main/java/org/apache/rocketmq/remoting/protocol/subscription/SubscriptionGroupConfig.java#L35) 默认重试 16 次，[GrpcClientSettingsManager](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/proxy/src/main/java/org/apache/rocketmq/proxy/grpc/v2/common/GrpcClientSettingsManager.java#L134) 将总投递次数设为 retryMaxTimes + 1。部署脚本保守要求重试次数本身不低于 SDK 上限。
- 供票 03：同包 `EventConsumer.handle(String consumerGroup, String eventId, String tenantId, String topic, String tag, byte[] payload)` 复用原消费的去重/租户恢复/事务，失败抛出；无 Handler 也抛出，不能误记 REPLAYED。必须传 `original_tenant_id`，不能把非法原始头替换为 NULL 后执行。数据库 `tenant_id` 为可解析 UUID，供展示/清理；原始头与 payload 都仅限内部使用。
- 表已提供 PENDING/REPLAYED/ABANDONED 状态与 resolved_at/resolution_reason；票 03 负责条件处置、90 天清理和 provisioning 的 TenantDataCleaner 适配（provisioning 已依赖 mq，mq 不反向依赖以免成环）。模块全量验证由集成分支交付前统一执行。

### 2026-11-07 最终审查修正

- 首次捕获的 `lastError` 改为仅保存静态异常类型；异常消息与根因消息可能包含 payload 值，不进入运维元数据。捕获和失败重放共用同一小型摘要，完整异常继续写入所属服务日志；接入指南同步说明。
- 经真实消费入口投递数字解析、UUID 解析、带 payload 值的 Handler 异常，再通过 HTTP 查询：旧实现 3 项均因独特 SECRET 值泄露而红灯，最小修复后 3 项全部通过。
- 定向回归 `DeadEventCaptureTest` 11 项、`DeadEventDisposalTest` 13 项全部通过；未重复模块全量。`DeadEventMapper` 接口统一说明不受 Data Policy 过滤。
