# 三类成功事实的 Audit Record 消费闭环

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

**实施 Issue：[#76](https://github.com/crane199709/saas-forge/issues/76)**

本规格只将 Session Started、Tenant Created、Tenant Context Switched 三类已提交成功事实映射为只追加 Audit Record，并完成真实 Kafka/PostgreSQL消费、去重、重试、隔离和受控重放。架构取舍见 [ADR 0023](adr/0023-audit-records-use-append-only-runtime-privileges.md)、[ADR 0024](adr/0024-service-owned-transactional-outbox.md) 与 [ADR 0035](adr/0035-audit-records-do-not-use-tenant-rls.md)。

## 1. 当前事实与差距

当前已验证状态：

- Audit Service只有 Web、Actuator、Nacos Config/Discovery与 readiness骨架；
- 没有 Kafka Consumer、JDBC/MyBatis运行实现或正式 Audit迁移；
- `V1__verify_uuidv7.sql` 只验证 PostgreSQL原生 UUIDv7；
- `audit_records`、消费去重、隔离和重放表都不存在；
- `audit_app` 的只追加权限只有质量门临时夹具证明，尚未由 Audit Flyway实现；
- Session Started与 Tenant Created已有 Schema、工程注册、生产 Outbox和真实提交路径；
- Tenant Context Switched已有生产工厂、真实变更事务和 no-op不发事件测试，但缺正式 Schema与工程注册；
- `audit.recorded.v1` 只有 Schema，没有工程注册、生产者或消费者；
- 事件 README与 Transactional Outbox文档仍含“注册表为空/没有业务切片”的过时陈述；
- 仓库没有可复用的生产 Consumer、Ack、隔离或重放实现样板。

因此本规格必须建立第一条生产 Consumer闭环，不能以 Controller存在、静态 Schema或 Mock测试宣称完成。

## 2. 交付与排除范围

只消费：

- `com.saas.forge.iam.session.started.v1`；
- `com.saas.forge.tenant.created.v1`；
- `com.saas.forge.iam.tenant-context-switched.v1`。

明确排除：

- 登录失败、密码错误、Identity不存在；
- 授权拒绝、Scope不足、Tenant切换失败或 no-op；
- IP、User Agent、Request ID安全分析；
- `/api/v1/audit` 查询、分页、导出和 UI；
- `sdk-audit`；
- `audit.recorded.v1` 生产与 Audit Outbox；
- IAM、Tenant Access、Entitlement其他已登记事件；
- 共享 Consumer平台、共享去重表或分布式事务。

失败尝试不能伪装成 Committed Fact Event。本规格只保存来源服务已经提交的成功事实。

## 3. 事件契约

### 3.1 Session Started

既有契约保持：

| 属性 | 值 |
|---|---|
| type | `com.saas.forge.iam.session.started.v1` |
| producer/source | `iam-service` / `urn:saas.forge:iam-service` |
| topic | `saas.forge.<environment>.iam-service.events` |
| ordering key | `identityId` |
| consumer | `audit-service.iam-session-events` |
| data | `familyId`、`identityId`、`purpose`、`contextType`、`result`、`occurredAt` |

### 3.2 Tenant Created

既有契约保持：

| 属性 | 值 |
|---|---|
| type | `com.saas.forge.tenant.created.v1` |
| producer/source | `tenant-access-service` / `urn:saas.forge:tenant-access-service` |
| topic | `saas.forge.<environment>.tenant-access-service.events` |
| ordering key | `tenantId` |
| consumer | `audit-service.tenant-events` |
| data | `tenantId`、`status=PENDING`、`actorIdentityId` |

### 3.3 Tenant Context Switched

新增正式文件并加入兼容性基线：

```text
saas-forge-contracts/saas-forge-event-contracts/iam-tenant-context-switched.v1.schema.json
```

登记：

| 属性 | 值 |
|---|---|
| type | `com.saas.forge.iam.tenant-context-switched.v1` |
| producer/source | `iam-service` / `urn:saas.forge:iam-service` |
| topic | `saas.forge.<environment>.iam-service.events` |
| ordering key | `identityId` |
| consumer | `audit-service.iam-session-events` |
| subject | `familyId` |
| data | `identityId`、`previousMembershipId`、`membershipId`、`tenantId` |

Schema必须与当前生产工厂一致；不得趁机新增请求来源、IP、User Agent、Token或其他代码没有提供的字段。no-op继续不发布事件。

### 3.4 统一安全边界

三类事件都必须验证：

- CloudEvents `specversion=1.0`；
- UUIDv7 `id`；
- 固定 source、type、topic、dataschema和 allowed consumer；
- `time` 为 RFC 3339 UTC；
- 可选 `traceId` 为非零小写 W3C Trace ID；
- data只包含各自 Schema白名单字段；
- 不含 Password、Access/Refresh Token、Authorization、Cookie、Client Secret、Digest、邮箱原文或其他凭据。

## 4. Audit Record

Audit Record只保存来源能够证明的规范化字段：

| 列 | 类型/约束 |
|---|---|
| `audit_record_id` | UUIDv7主键，默认 `uuidv7()` |
| `source_event_id` | UUIDv7、非空 |
| `source` | 固定来源 URN、非空 |
| `source_type` | 版本化事件 type、非空 |
| `occurred_at` | `timestamptz`、非空，取 CloudEvents `time` |
| `recorded_at` | `timestamptz`、非空，Audit提交时间 |
| `trace_id` | 可空、32位小写十六进制且非全零 |
| `actor_identity_id` | 可空 UUIDv7 |
| `tenant_id` | 可空 UUIDv7，只表示来源明确提供的 Tenant |
| `action` | 受控枚举、非空 |
| `resource_type` | 受控枚举、非空 |
| `resource_id` | UUIDv7、非空 |
| `result` | 本切片固定 `SUCCESS` |
| `metadata` | 非空 JSON object，只含逐事件白名单键 |

建立唯一约束 `(source, source_event_id)`，同时保留 Consumer去重键。Audit Record不保存完整 Envelope、Request ID、IP、User Agent或来源没有提供的 Membership/Tenant/Actor。

`audit_records` 不启用 Tenant RLS，`tenant_id` 可空；这一例外仅适用于 Audit Record，不允许业务表据此绕过 RLS。未来查询必须在 Audit服务层执行显式授权。

## 5. 三类事件映射

| 来源 | Actor | Tenant | Action | Resource | Result | Metadata |
|---|---|---|---|---|---|---|
| Session Started | `data.identityId` | `null` | `SESSION_STARTED` | `REFRESH_TOKEN_FAMILY / data.familyId` | `SUCCESS` | `purpose`、`contextType`、`sessionOutcome=data.result` |
| Tenant Created | `data.actorIdentityId` | `data.tenantId` | `TENANT_CREATED` | `TENANT / data.tenantId` | `SUCCESS` | `initialStatus=PENDING` |
| Tenant Context Switched | `data.identityId` | `data.tenantId` | `TENANT_CONTEXT_SWITCHED` | `REFRESH_TOKEN_FAMILY / subject` | `SUCCESS` | `previousMembershipId`、`targetMembershipId=data.membershipId` |

Session Started 的 `data.occurredAt` 必须与 CloudEvents `time` 相等；不相等属于永久映射不变式错误。Identity在 Session Started与 Context Switched中被明确映射为 Actor；这是一项 Audit解释规则，不回写或改变来源契约。

Metadata必须逐 type构造，不允许把 `data` 整体复制为 JSONB。Membership ID当前只在白名单 Metadata中保留；没有已确认查询需求前不增加正式列。

## 6. 数据库表与权限

在 Audit现有 V1之后新增前向迁移，建立：

### 6.1 `audit_records`

- 只追加规范化记录；
- `audit_app`：`SELECT, INSERT`；
- 明确撤销或不授予 `UPDATE, DELETE, TRUNCATE`；
- 无 `updated_at`、`deleted_at` 或软删除；
- 不启用 RLS。

### 6.2 `audit_consumed_events`

- 主键 `(consumer_name, event_id)`；
- 保存 source、source_type、consumed_at；
- 与 Audit Record在同一本地事务 INSERT；
- `audit_app`：`SELECT, INSERT`；
- 禁止 `UPDATE, DELETE, TRUNCATE`。

### 6.3 `audit_consumer_isolations`

- 保存 isolation ID、consumerName、topic/partition/offset、可选 event ID/source/type、payload SHA-256、失败类别、attempt count、first/last failure、状态和可选安全快照；
- 状态为 `OPEN`、`REPLAY_REQUESTED`、`RESOLVED`、`REJECTED_NON_REPLAYABLE`；
- `audit_app`：`SELECT, INSERT, UPDATE`；
- 禁止 `DELETE, TRUNCATE`。

### 6.4 `audit_isolation_attempts`

- 每次失败、隔离、重放请求和处置写一条只追加轨迹；
- `audit_app`：`SELECT, INSERT`；
- 禁止 `UPDATE, DELETE, TRUNCATE`。

### 6.5 `audit_isolation_deliveries`

- 与隔离记录同事务建立消费者自有隔离 Topic的可靠投递状态；
- 保存不可变安全快照引用、topic、key、领取租约、尝试次数、下次尝试和完成时间；
- `audit_app`：`SELECT, INSERT, UPDATE`；
- 禁止 `DELETE, TRUNCATE`。

所有运行表由 `audit_migrator` 创建和授权。真实 PostgreSQL测试必须以 `audit_app` 证明允许和禁止操作；不能只验证 SQL文本。

## 7. Consumer 拓扑

| consumerName | 输入 Topic | 处理类型 |
|---|---|---|
| `audit-service.iam-session-events` | IAM events | Session Started、Tenant Context Switched |
| `audit-service.tenant-events` | Tenant Access events | Tenant Created |

Tenant Suspended 与 Tenant Created 共用 Tenant Access Topic，但工程注册表将其授权给
`audit-service.tenant-lifecycle-events`。当前 `audit-service.tenant-events` 只校验并确认该合法事件，
计入 `ignored`，不写去重或 Audit Record，也不占用生命周期审计 Consumer 的处理状态。

每个 Consumer只处理工程注册表授权给自己的 type：

- 已登记且属于其他 Consumer的合法事件：确认、增加 `ignored` 指标，不写去重、不隔离；
- 未登记 type、错误 source、错误 topic或 ACL边界不符：安全拒绝并隔离；
- 不使用跨服务共享 DLQ或共享 Consumer表。

Kafka record key继续由 producer ordering key决定；Audit不承诺跨 Identity或跨 Tenant全局顺序。

## 8. 消费事务、去重与确认

处理顺序固定为：

```text
读取 Kafka record
  → 校验 topic/source/type/allowed consumer
  → 校验 CloudEvents Envelope 与独立 Schema
  → 校验映射不变式和安全字段白名单
  → 开启本地事务
      INSERT audit_consumed_events
      INSERT audit_records
    提交
  → 确认 Kafka
```

重复事件命中 `(consumer_name,event_id)` 时不再写 Audit Record，直接视为成功处理。去重 INSERT和 Audit Record必须在同一事务：任一失败全部回滚，不能先去重后丢失副作用，也不能先写记录后重复。

Kafka只能在成功事务或持久隔离事务提交后确认。进程在提交后、确认前故障会重投，并由去重键安全吸收。

## 9. 失败分类与重试

### 9.1 可重试

- PostgreSQL短暂不可用；
- 事务冲突或可恢复锁异常；
- 进程或运行时未知异常；
- 其他没有被明确分类为永久错误的处理失败。

默认最多 10次，指数退避从 `PT1S` 开始，最大 `PT1M`。超过阈值后进入持久隔离。

### 9.2 永久错误

- Envelope或 Schema非法；
- source/type/topic不一致；
- type未登记或 Consumer未获授权；
- Session时间不变式冲突；
- 映射字段非法或安全字段白名单违反。

永久错误首次失败即持久隔离，不进行十次无意义重试。属于其他合法 Consumer的事件是 `ignored`，不是失败。

## 10. 隔离与安全快照

Schema和安全白名单都通过、但处理失败耗尽重试时：

- 隔离记录可以保存完整不可变 Envelope；
- 同事务写入 `audit_isolation_deliveries`；
- 原消息在事务提交后确认；
- 后台发布器以可接管租约发送到 Consumer自有隔离 Topic；
- 发送失败持续重试，不改变原 Event ID。

Envelope、Schema或安全白名单失败时：

- 不保存或重新发布原 payload；
- 只保存 topic、partition、offset、可解析 event ID、payload SHA-256、失败类别和脱敏摘要；
- 状态直接为 `REJECTED_NON_REPLAYABLE`；
- 不允许 Audit修补消息后重放。

这样避免为重放永久保存可能夹带 Token或 Secret的非法输入。

## 11. 人工重放

提供非 Web `Audit Isolation Replay Job`：

- 输入只接受 isolation ID，不接受任意 payload；
- 只允许状态 `OPEN` 且存在已验证安全快照的记录；
- 重复请求幂等，不创建多个并发重放；
- 发布时保留原 Event ID、source、type、time、traceId和 data；
- 状态按 `OPEN → REPLAY_REQUESTED → RESOLVED` 迁移；
- 每次请求、发送、再次失败和成功写入 `audit_isolation_attempts`；
- 处置完成后保留隔离记录和全部轨迹。

非法来源只能由权威 Producer修正并以原 Event ID走受控重投；Audit不能生成或篡改来源事实。

## 12. Nacos、Kafka 与 Ready

Kafka bootstrap地址继续通过部署环境变量提供。Audit专属 Nacos资源保存：

```yaml
saas.forge:
  audit:
    configuration-revision: "<incremented>"
    consumer:
      max-attempts: 10
      initial-backoff: PT1S
      max-backoff: PT1M
    isolation:
      publish-delay: PT1S
      lease-duration: PT30S
```

具体属性命名可按实现绑定类调整，但不得改变已确认语义。四个环境都必须显式登记并递增各自 revision；`refreshEnabled=false`。

Audit Ready同时要求：

- Nacos配置有效；
- 数据库可访问且正式迁移已经应用；
- 两个 Kafka Consumer已启动并获得分区分配。

任一条件丢失时 Ready=false；Liveness保持 UP以允许进程恢复。数据库或 Kafka恢复后可以重新 Ready，不要求重启。

## 13. `audit.recorded.v1`

本切片保留既有 Schema，但：

- 不加入工程注册表；
- 不建立 Audit领域 Outbox；
- 不生产该事件；
- 不为不存在的消费者构建发布、重试和恢复链路。

首个真实消费者出现时，必须重新评审用途、allowed consumer、Topic、留存和直接验收，再决定是否实现。

## 14. 测试矩阵

契约：

1. Tenant Context Switched Schema与当前生产 payload一致；
2. 三类事件的 source/type/topic/ordering key/consumer一致；
3. Envelope、Schema和安全字段白名单通过；
4. Token、Authorization、Secret、Digest、Password等字段被拒绝；
5. `audit.recorded.v1` 仍未注册和生产。

真实 PostgreSQL + Kafka：

1. 三类合法事件各产生一条正确映射的 Audit Record；
2. 重复消息不产生重复记录；
3. 去重与 Audit Record原子提交；
4. Schema非法消息不产生 Audit Record或去重副作用；
5. Trace ID保持；
6. Session data时间不一致立即隔离；
7. transient失败按阈值退避重试；
8. 超限后先提交隔离记录和投递状态，再确认原消息；
9. Schema非法消息不保存原文；
10. 人工重放复用原 Event ID并保留处置轨迹；
11. `audit_app` 不能更新、删除或截断 Audit Record；
12. 可变隔离表只能获得所需 UPDATE，不能 DELETE/TRUNCATE；
13. Tenant Context Switch no-op不产生事件或 Audit Record；
14. 同 Topic其他已登记事件被忽略而非隔离；
15. 数据库或 Kafka不可用时 Ready=false，恢复后重新 Ready。

Compose必须使用真实 PostgreSQL、Kafka、IAM、Tenant Access和 Audit Service。可以通过真实业务入口产生三类事件，也可以对 Consumer反向分支投递受控测试消息；Mock-only Consumer不能作为完整证据。

## 15. 文件影响范围

预计涉及：

- Tenant Context Switched Schema、工程注册和兼容性基线；
- 事件 README与 Transactional Outbox过时说明；
- Audit POM、应用配置、Flyway、Mapper XML、Consumer、隔离发布器、Replay Job与测试；
- `deploy/nacos/{dev,test,staging,prod}/audit-service.yaml`；
- Compose、Kafka ACL和真实验收脚本；
- PostgreSQL质量门与 Audit权限验证；
- 数据库、部署、测试、MVP计划文档；
- ADR 0035。

不得修改三个 producer的业务语义，除补齐既有 Tenant Context Switched正式契约和必要契约一致性外，不扩展事件 payload。

## 16. 实施顺序（to-spec）

1. 先补 Tenant Context Switched Schema、工程注册和兼容性测试；修正文档漂移。
2. 为 Audit加入根 POM已管理的 PostgreSQL、MyBatis、Kafka依赖，不在子模块声明版本。
3. 编写前向 Flyway迁移，建立五张职责分离表、约束、索引和最小权限。
4. 先用真实 PostgreSQL验证 `audit_app` 允许/禁止矩阵。
5. 实现 Schema/Registry加载、两个 Consumer和三类白名单映射。
6. 实现本地事务去重、Audit Record写入和 commit-after-ack测试。
7. 实现失败分类、10次退避、持久隔离和可靠隔离 Topic投递。
8. 实现非 Web Replay Job和处置轨迹。
9. 更新四环境 Audit Nacos配置、revision、Kafka ACL和 Ready检查。
10. 完成真实 Kafka/PostgreSQL集成测试和 Compose三类成功事实验收。
11. 运行 Audit相关模块 `verify`、完整质量门和 Nacos配置校验，记录证据。

## 17. 完成标准

只有以下全部成立，Issue才可关闭：

- 三类生产成功事实都有正式 Schema/注册并产生一条准确 Audit Record；
- 重复消费、事务原子性、ack顺序有真实 Kafka/PostgreSQL证据；
- 永久错误、可重试错误、超限隔离和原 ID重放均按规格工作；
- 非法 payload不进入 Audit数据库或隔离 Topic原文；
- `audit_app` 最小权限由真实 PostgreSQL证明；
- no-op和来源未提供字段没有被伪造；
- 四环境 Nacos配置和 Ready门禁通过；
- `/api/v1/audit`、导出、UI、失败登录审计和 `audit.recorded.v1` 没有被顺带实现。

完成本规格只勾选 MVP总项的 Audit子项。Gateway子项另见 [Gateway 通用路由目录与 User/Service Token Scope 策略](23-gateway-service-scope-routing.md)；父项必须等待两个子项都有直接证据。
