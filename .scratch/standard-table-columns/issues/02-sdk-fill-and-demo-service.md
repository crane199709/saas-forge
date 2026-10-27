# 02: SDK 统一填充与逻辑删除，demo-service 落地

**What to build:** 业务服务的实体只要继承 `AuditableEntity`，就能自动得到创建/修改时间、操作人填充和逻辑删除，业务代码不需要写任何赋值。demo-service 的 `orders`、`order_notifications` 补齐标准列，用来做端到端验收。

**Blocked by:** demo-service 未提交的 MQ 工作（`V4__messaging.sql` 等）先提交；本工单的迁移排在 `V4` 之后

**Status:** ready-for-agent

- [ ] `forge-sdk-core` 新增 `CurrentActorProvider`（返回 `Optional<UUID>`），要有文档注释
- [ ] `forge-sdk-security` 注册实现：有已认证的 `JwtAuthenticationToken` 时返回 `sub`，否则返回空，不抛异常
- [ ] `forge-sdk-tenant` 新增 `AuditableEntity` 与 `MetaObjectHandler`，并在 `TenantAutoConfiguration` 中注册；按 spec 第 8 节实现强制覆盖语义，通过 `ObjectProvider` 获取操作人，拿不到时填 NULL；全局配置逻辑删除字段 `deleted`
- [ ] SDK 不新增模块级或包级循环依赖：tenant 与 security 互不依赖
- [ ] demo-service 新增迁移：`orders` 补 `updated_at`、`created_by`、`updated_by`、`deleted`；`order_notifications` 补齐五列；存量行回填
- [ ] `Order`、`OrderNotification` 继承 `AuditableEntity`，并删掉 `Order` 中自己声明的 `createdAt`；接口响应 `record` 是否增加字段，以现有排序和展示需求为准，不额外扩展
- [ ] `OrderMapper.xml` 中的手写查询加上 `NOT deleted`
- [ ] 确认 MyBatis-Plus 3.5.16 中 `deleteById(id)` 在逻辑删除时是否触发更新填充，把结论写进本工单的 Comments，并补到 `coding-standards.md` 第 9 节第 4 条
- [ ] 测试（demo-service 集成测试）：
  - 已认证请求创建订单时，`created_*`、`updated_*` 被填充，`*_by` 等于当前用户的 identity_id
  - 修改订单时只刷新 `updated_*`，`created_*` 不变；即使调用方传入时间或操作人，也会被覆盖
  - 删除订单后，列表和详情都查不到，数据库中该行 `deleted = true`，且 `updated_*` 已刷新
  - 由 MQ 消费写入的 `order_notifications`，`*_by` 为 NULL
  - 租户隔离测试仍然通过
- [ ] 后端 `./mvnw verify` 通过

## Comments
