# 建表标准列与 MyBatis-Plus 统一填充规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-10-27 的设计拷问逐条确认，落地后由 ADR-0016 记录。

## 1. 背景与目标

现有建表规范只约束 `tenant_id` + RLS（ADR-0001）与 UUIDv7 主键（ADR-0014）。审计类字段各表自行其是：`orders` 事后补了 `created_at`，`order_notifications` 与 IAM 的 `tenant`、`identity`、`membership`、`operator` 没有任何时间或操作人字段；仓库中没有逻辑删除，也没有 `MetaObjectHandler`。

目标：

1. 定义所有业务实体表必须带的标准列，新表照此建表，存量表一次性补齐。
2. 时间、操作人、逻辑删除由 SDK 经 MyBatis-Plus 统一处理，业务代码不手写赋值。
3. 明确自动处理覆盖不到的写法由谁负责。

## 2. 适用范围

| 表 | 是否带标准列 |
|---|---|
| 租户业务表（带 `tenant_id` + RLS，如 `orders`、`order_notifications`） | 是 |
| IAM、platform 中有业务语义的实体表（`tenant`、`identity`、`membership`、`operator` 及后续同类表） | 是 |
| SDK 基础设施表（`forge_outbox`、`forge_consumed_message`） | 否 |
| 纯关联表、日志表、审计分区表 | 否 |

以下称「标准表」。

## 3. 标准列

```sql
created_at timestamptz NOT NULL DEFAULT now(),
updated_at timestamptz NOT NULL DEFAULT now(),
created_by uuid,
updated_by uuid,
deleted    boolean     NOT NULL DEFAULT false
```

- `*_by` 不建外键：操作人可能在其他 schema，或属于 Platform Realm。
- 两个时间列都带 `DEFAULT now()`，供迁移回填和 seed SQL 使用；经 MyBatis-Plus 写入时由 SDK 覆盖。
- 乐观锁 `version` 不放进标准列，需要的表自行添加，例如 `membership.version`。
- 不设 `deleted_by`、`deleted_at`：删除时刷新 `updated_at`、`updated_by`，可以据此知道是谁、在什么时候删除的。

## 4. 操作人语义

- `*_by` 存当前请求已验证 JWT 的 `sub`。每个服务只属于一个 realm（`forge.security.realm`），所以同一个服务里这一列只有一种 ID：Tenant Realm 服务存 `identity.id`，Platform Realm 服务存 `operator.id`。
- 没有认证上下文时填 NULL，表示「系统」。这包括 MQ 消费者、定时任务、Outbox 投递器、登录流程中的写入，以及租户清理。
- 不跨线程或跨消息传递操作人。「因为谁的操作才产生了这次写入」由 Audit Event（MVP 工单 23）回答，标准列只回答「这一行最后是谁写的」。

## 5. 与 Audit Event 的分工

标准列只保存最近一次写入的快照，用于列表展示、排序和显示「最后修改人」。变更历史与合规审计只由 Audit Event 负责。

## 6. 逻辑删除

- 所有标准表都带 `deleted`，SDK 全局配置 MyBatis-Plus 逻辑删除（`logic-delete-field: deleted`）。标准表上的删除一律是逻辑删除。
- `status` 继续表达业务状态（停用、过期、归档、Tenant 生命周期中的 deleted 状态）；`deleted = true` 只表示该行对常规查询不可见。Tenant 处于 deleted 状态的冷静期内，tenant 行仍然可见，以便撤回。
- **租户冷静期结束后的清理也是逻辑删除**：各服务在 `CurrentTenant.run(tenantId, ...)` 中，把该租户的数据批量标记为已删除。这条批量更新写在 mapper XML 中：`UPDATE ... SET deleted = true, updated_at = now()`，`updated_by` 保持 NULL。本期不支持物理清除租户数据；如果以后有合规擦除或存储方面的需求，另行决策。
- 不用 RLS 过滤已删除行：RLS 只负责租户隔离，否则恢复、后台查看与清理都需要绕过 RLS。

## 7. 唯一约束

标准表禁止使用 `UNIQUE` 列约束或表约束，唯一性一律用部分唯一索引表达：

```sql
CREATE UNIQUE INDEX identity_account_key ON identity (account) WHERE NOT deleted;
```

否则账号删除后无法重新注册，成员移除后也无法再次加入。存量约束一并改造：`identity.account`、`identity_email_lower_key`、`membership(identity_id, tenant_id)`、`operator.account`。

## 8. SDK 实现

- `forge-sdk-core` 新增窄接口 `CurrentActorProvider`，返回 `Optional<UUID>`。
- `forge-sdk-security` 提供实现：存在已认证的 `JwtAuthenticationToken` 时返回 `sub`，否则返回空，不抛异常。
- `forge-sdk-tenant` 提供：
  - 抽象基类 `AuditableEntity`：四个审计字段 + `deleted`，附 getter/setter、`@TableField(fill = ...)` 和逻辑删除注解；`tenant_id` 仍不在实体中声明。
  - `MetaObjectHandler`：通过 `ObjectProvider<CurrentActorProvider>` 获取操作人，Bean 不存在或返回空时填 NULL。采用**强制覆盖**语义：INSERT 时写 `created_*` 和 `updated_*`（`updated_at` 等于 `created_at`），UPDATE 时只写 `updated_*`，调用方传入的值一律忽略。MyBatis-Plus 的 `strictUpdateFill` 只填 null 字段，先查后改的实体不会刷新，因此不能使用。
  - 全局逻辑删除配置。
- 依赖方向：tenant → core ← security，tenant 与 security 互不依赖。

## 9. 自动处理覆盖不到的写法

以下写法不会触发填充或逻辑删除条件，由开发者负责，并写进编码规范：

1. mapper XML 中手写的 INSERT/UPDATE：自己维护 `updated_at`、`updated_by`，INSERT 还要维护 `created_*`。
2. 不带实体的更新，例如 `update(null, wrapper)`：同上。
3. mapper XML 中手写的查询：每张标准表（包括 JOIN 进来的表）都必须带 `NOT deleted`。
4. 删除必须使用能触发更新填充的方法。`deleteById(id)` 在逻辑删除时是否触发填充，由工单 02 的测试确认，并据此写进规范。

本期不做启动校验、静态检查或自定义拦截器。

## 10. 存量迁移

- iam 与 demo-service 各新增一个 Flyway 版本：补列、回填（时间取迁移执行时间，`*_by` 为 NULL，`deleted = false`），并改造唯一约束。已发布的迁移不改写。
- `orders.created_at` 已经存在且定义符合标准，保持不变，只补其余列。
- demo-service 的新迁移排在 `V4__messaging.sql` 之后，`V4` 需要先随 MQ 工作提交。
- IAM 目前没有实体类，全部 SQL 都写在 mapper XML 中，因此按第 9 节手工维护标准列和 `NOT deleted`。

## 11. 文档

- 新增 ADR-0016，记录：标准列集合；逻辑删除进基线（包括租户清理）及其代价；部分唯一索引；MyBatis-Plus 统一填充与强制覆盖；操作人语义与「不传递操作人」；本期不支持物理清除。
- 更新 `docs/agents/coding-standards.md` 的建表规范与第 9 节规则，并更新 AGENTS.md 的摘要。
- 在 ADR-0001 的建表要求旁边加交叉引用。
- 把 GLOSSARY（Cooling-off Period 等）与 MVP spec 中冷静期后「真正清理」「清理本服务数据」的表述，改为「标记删除」。
