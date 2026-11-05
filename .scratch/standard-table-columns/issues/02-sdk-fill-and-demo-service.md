# 02: SDK 统一填充与逻辑删除，demo-service 落地

**What to build:** 业务服务的实体只要继承 `AuditableEntity`，就能自动得到创建/修改时间、操作人填充和逻辑删除，业务代码不需要写任何赋值。demo-service 的 `orders`、`order_notifications` 补齐标准列，用来做端到端验收。

**Blocked by:** 无

**Status:** resolved

- [x] `forge-sdk-core` 新增 `CurrentActorProvider`（返回 `Optional<UUID>`），要有文档注释
- [x] `forge-sdk-security` 注册实现：有已认证的 `JwtAuthenticationToken` 时返回 `sub`，否则返回空，不抛异常
- [x] `forge-sdk-tenant` 新增 `AuditableEntity` 与 `MetaObjectHandler`，并在 `TenantAutoConfiguration` 中注册；按 spec 第 8 节实现强制覆盖语义，通过 `ObjectProvider` 获取操作人，拿不到时填 NULL；全局配置逻辑删除字段 `deleted`
- [x] SDK 不新增模块级或包级循环依赖：tenant 与 security 互不依赖
- [x] demo-service 新增迁移：`orders` 补 `updated_at`、`created_by`、`updated_by`、`deleted`；`order_notifications` 补齐五列；存量行回填
- [x] `Order`、`OrderNotification` 继承 `AuditableEntity`，并删掉 `Order` 中自己声明的 `createdAt`；接口响应 `record` 是否增加字段，以现有排序和展示需求为准，不额外扩展
- [x] `OrderMapper.xml` 中的手写查询加上 `NOT deleted`
- [x] 确认 MyBatis-Plus 3.5.16 中 `deleteById(id)` 在逻辑删除时是否触发更新填充，把结论写进本工单的 Comments，并补到 `coding-standards.md` 第 9 节第 4 条
- [x] 测试（demo-service 集成测试）：
  - 已认证请求创建订单时，`created_*`、`updated_*` 被填充，`*_by` 等于当前用户的 identity_id
  - 修改订单时只刷新 `updated_*`，`created_*` 不变；即使调用方传入时间或操作人，也会被覆盖
  - 删除订单后，列表和详情都查不到，数据库中该行 `deleted = true`，且 `updated_*` 已刷新
  - 由 MQ 消费写入的 `order_notifications`，`*_by` 为 NULL
  - 租户隔离测试仍然通过
- [x] 后端 `./mvnw verify` 通过

## Comments

2026-10-27 实现：`forge-sdk-core` 新增 `CurrentActorProvider`；`forge-sdk-security` 新增 `JwtCurrentActorProvider`（已认证的 `JwtAuthenticationToken` 返回 `sub`，否则为空、不抛异常），在 `ForgeSecurityAutoConfiguration` 中以 `@ConditionalOnMissingBean` 注册。`forge-sdk-tenant` 新增 `AuditableEntity`（四个审计字段 + `@TableLogic deleted`；`created_*` 为 `fill = INSERT` 且 `updateStrategy = NEVER`，`updated_*` 为 `INSERT_UPDATE`）与包内 `AuditColumnsMetaObjectHandler`（只处理 `AuditableEntity`，直接 `setValue` 强制覆盖：不用 `strictFill`，也不用会跳过 null 的 `setFieldValByName`；时间截断到微秒，与 `timestamptz` 一致），`TenantAutoConfiguration` 注册该 handler（`@ConditionalOnMissingBean(MetaObjectHandler.class)`，经 `ObjectProvider<CurrentActorProvider>` 取操作人）和 `MybatisPlusPropertiesCustomizer`（全局 `logic-delete-field: deleted`，取值 `true`/`false`，因为 PostgreSQL boolean 不能与默认的 `1`/`0` 比较）。tenant 与 security 都只依赖 core，互不依赖。demo-service 新增 `V5__standard_columns.sql`（`orders` 补四列，`order_notifications` 补五列，存量行靠列默认值回填）；`Order`、`OrderNotification` 继承 `AuditableEntity`，删掉 `Order.createdAt`；`OrderMapper.xml` 加 `WHERE NOT deleted` 并补选标准列。`OrderResponse` 不变：现有排序与展示只用到 `createdAt`。
- 偏离：修了 `forge-sdk-mq` 的一个潜在缺陷。`MqAutoConfiguration` 原先在 `ConfigurationCustomizer` 里 `addMapper`，这一步早于 `MybatisSqlSessionFactoryBean` 把 GlobalConfig 绑定到 Configuration，所以会先绑上一份默认 GlobalConfig；而 `GlobalConfigUtils.setGlobalConfig` 用的是 `putIfAbsent`，服务的 `MetaObjectHandler` 与逻辑删除配置因此全部被丢掉（实测 handler 为空、INSERT 写入 NULL）。现在改为在 `EventPublisher`、`OutboxDispatcher`、`EventConsumer` 的 Bean 方法中，等 SqlSessionFactory 建好后按需 `addMapper`（`MqAutoConfiguration.mapper`），并删除 `mqMapperCustomizer`。规范里可以补一条：SDK 不要在 `ConfigurationCustomizer` 中注册 Mapper。
- `deleteById` 结论（请并入 `coding-standards.md` 第 9 节第 4 条，本工单没有改这个文件）：MyBatis-Plus 3.5.16 中，实体同时带逻辑删除字段和更新填充字段时，`deleteById(id)`（即 `deleteById(id, true)`）会先用主键 new 一个实体，再按实体逻辑删除，**因此会触发更新填充**，`updated_at`、`updated_by` 随删除刷新。这一点已从字节码确认，并由测试验证：先由系统修改把 `updated_by` 置为 NULL，再经接口删除，`updated_by` 变为当前 identity。规范可写为：标准表按主键删除用 `deleteById`；`delete(wrapper)`、`deleteByMap` 等按条件删除不带实体，按实现推断不会触发填充（未单独测试），标准表上不要使用。spec 第 9 节第 4 条已同步。
- 验证：新增 `StandardColumnsTest` 3 项：已认证创建时填 `created_*` 和 `updated_*`（两个时间相等，`*_by` 为 identity）；修改只刷新 `updated_*`，无认证的修改把 `updated_by` 写为 NULL；删除后详情 404、再次删除 404、列表和 `totalElements` 都不含该行，库中 `deleted = true` 且 `updated_*` 已刷新；在 Mapper 层插入和先查后改时传入伪造的时间与操作人，会被覆盖，`created_*` 不被 UPDATE 改写。`OutboxMessagingTest` 断言 MQ 消费写入的 `order_notifications` 时间已填充、`*_by` 为 NULL。`TenantIsolationTest`、`OrderApiTest` 不改动仍通过。完整 `./mvnw verify` 通过（含 spotless，263 项测试）。
