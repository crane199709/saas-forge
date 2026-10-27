# 标准表统一带审计列与逻辑删除，由 MyBatis-Plus 强制填充

租户业务表与 IAM、platform 中有业务语义的实体表（「标准表」）一律带标准列 `created_at`、`updated_at`、`created_by`、`updated_by`、`deleted`，完整定义与适用范围见 [建表标准列规格](../../.scratch/standard-table-columns/spec.md)。SDK 基础设施表（`forge_outbox`、`forge_consumed_message`）、纯关联表、日志表与审计分区表不适用。此前审计字段各表自行其是，有的表事后补 `created_at`，有的表没有任何时间或操作人字段；统一后列表排序、「最后修改人」展示与删除语义在各服务一致，业务代码也不再手写赋值。

- **列集合从简**：两个时间列 `timestamptz NOT NULL DEFAULT now()`，`DEFAULT` 供迁移回填与 seed SQL 使用；`*_by` 为 `uuid`，不建外键，因为操作人可能在其他 schema 或属于 Platform Realm。不设 `deleted_by`、`deleted_at`：删除时刷新 `updated_*`，已能回答谁在何时删除。乐观锁 `version` 不放进标准列，需要的表自行添加。
- **逻辑删除进基线**：所有标准表带 `deleted`，SDK 全局配置 MyBatis-Plus 逻辑删除，标准表上的删除一律是逻辑删除。`status` 仍表达业务状态，`deleted = true` 只表示该行对常规查询不可见。租户冷静期结束后的清理也是逻辑删除：各服务在 `CurrentTenant.run(tenantId, ...)` 中用 mapper XML 批量 `UPDATE ... SET deleted = true, updated_at = now()`，`updated_by` 保持 NULL。代价：
  - 唯一约束必须改为 `WHERE NOT deleted` 的部分唯一索引，否则账号删除后无法重新注册、成员移除后无法再次加入；标准表禁止 `UNIQUE` 约束，存量的 `identity.account`、`identity_email_lower_key`、`membership(identity_id, tenant_id)`、`operator.account` 一并改造。
  - mapper XML 中手写的查询不经过逻辑删除改写，每张标准表（包括 JOIN 进来的表）都要自带 `NOT deleted`，漏写即读到已删除数据。
  - 数据不会被物理清除，表体积只增不减。本期不支持物理清除租户数据；以后有合规擦除或存储方面的需求时另行决策。
- **统一填充、强制覆盖**：SDK 提供 `AuditableEntity` 基类与 `MetaObjectHandler`。INSERT 写 `created_*` 与 `updated_*`（`updated_at` 等于 `created_at`），UPDATE 只写 `updated_*`，调用方传入的值一律忽略。不用 MyBatis-Plus 的 `strictUpdateFill`：它只填 null 字段，先查后改的实体带着旧值，`updated_*` 不会刷新。手写 XML 的 INSERT/UPDATE、不带实体的更新不触发填充，由开发者自行维护标准列；本期不做启动校验、静态检查或自定义拦截器。
- **操作人语义**：`*_by` 存当前请求已验证 JWT 的 `sub`。每个服务只属于一个 realm，所以同一服务中这一列只有一种 ID（Tenant Realm 服务存 `identity.id`，Platform Realm 服务存 `operator.id`）。没有认证上下文时填 NULL，表示「系统」，包括 MQ 消费者、定时任务、Outbox 投递器、登录流程中的写入与租户清理。操作人通过 `forge-sdk-core` 的 `CurrentActorProvider` 获取，由 `forge-sdk-security` 实现，`forge-sdk-tenant` 只依赖该接口，两者互不依赖。
- **不传递操作人**：不跨线程或跨消息传递操作人。标准列只回答「这一行最后是谁写的」，「因为谁的操作才产生了这次写入」与变更历史由 Audit Event 负责。

## Considered Options

- `deleted_at timestamptz` 代替 `deleted boolean`：可以省掉删除时间，但删除时已刷新 `updated_at`，信息重复；部分唯一索引与手写查询的条件也更长（`WHERE deleted_at IS NULL`）。
- 按表 opt-in 逻辑删除：哪张表是逻辑删除要逐表记忆，手写 SQL 漏写 `NOT deleted` 或误用物理删除的风险更高；全量进基线后规则只有一条。
- 用 RLS 过滤已删除行：RLS 只负责租户隔离（ADR-0001）。若同时过滤已删除行，恢复、后台查看与租户清理都要绕过 RLS，削弱兜底隔离。
- 跨线程、跨消息传递操作人（如在 MQ 消息头携带）：消费者写入的行记成发起人，会混淆「谁写的」与「因谁而写」，并让消息契约依赖调用链；这部分由 Audit Event 回答。
