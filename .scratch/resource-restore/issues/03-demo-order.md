# 03: demo Order 恢复

**What to build:** 在 demo-service 实现 Order 恢复，作为接入范本。

**Blocked by:** 01

**Status:** resolved

- [x] 提供已删除列表和恢复接口，手写 SQL 符合编码规范
- [x] 恢复后 Business Number 不变，Data Policy 仍然生效
- [x] 记录 `order.restore`，跨租户测试通过
- [x] 接入指南引用此示例

## Comments


## Answer

已在 demo-service 完成 Order 已删除视图与 Restore，并以现有订单代码作为 Business Service 接入范本。

### API 契约

- `GET /orders` 与 `GET /orders?deleted=false` 保持 `order:read`，只查询未删除订单。`GET /orders?deleted=true` 使用 `order:delete`，只查询已删除订单；沿用 `page`、`size`、`sort=createdAt|amount,asc|desc` 和现有分页响应信封，列表与数量都受当前 Tenant 和 Data Policy 过滤。
- `OrderResponse` 保留现有字段，追加 `updatedAt`、`updatedBy`：已删除视图中可展示删除时间与操作人的 Identity ID，系统操作人的值为 null。
- `POST /orders/{id}/restore` 沿用删除权限 `order:delete`，成功返回 HTTP 200 的 `ApiResponse.ok()`。目标不存在、未删除、当前租户外或 Data Policy 范围外统一返回 `order.notFound` / 404，重复与并发恢复只成功一次。
- 恢复专用读取以 `deleted = true` 和 `FOR UPDATE` 获取数据库行锁，与现有删除 UPDATE 使用同一行锁；事务内的读取、更新都不绕过租户拦截、RLS 或 Data Policy。UPDATE 带 ID 和 `deleted = true`，检查影响行数，刷新 `updated_at`、从 `CurrentActorProvider` 取得的 `updated_by`，保留创建信息和原有业务字段。
- 唯一性沿用现有 `(tenant_id, number) WHERE NOT deleted` 的 `orders_number_key`。只把该索引的 23505 重复键错误映射为 SDK `resource.restoreConflict` / 409；失败回滚后订单仍在已删除视图，处理占用记录后可以恢复。不新增名称限制，也不生成新 Business Number。
- `@AuditLog(action = "order.restore", ...)` 记录成功与失败。Restore 保留订单原有 `unpaid` / `closed` 状态，不重开 Todo、不重发 Notification，也不再次关闭 Todo。
- [接入指南](../../../docs/business-service-guide.md#资源恢复) 已引用实际 OrderController、OrderService、Mapper XML 与 OrderRestoreTest。

### TDD 与验证

测试边界为已约定的 HTTP 接口及公开出站消息契约；订单结果均经 HTTP 观察，审计、Todo、Notification 沿用现有 Outbox 夹具观察出站消息。新增测试共用模块的 `SharedContainers`，包含独立数据库与 Redis database 配置，每个用例使用独立租户。

按行为片段完成 red → green：已删除查询错误返回有效行 → 正确分页与删除元数据；恢复端点 404 → 恢复成功且编号和状态保持；缺少恢复审计事件 → 成功/失败事件齐全；真实部分唯一索引冲突 500 → 共享 409，处理冲突后恢复成功。权限、Data Policy 与租户隔离沿用既有机制，补充公开接口验证；并发恢复只有一项成功，恢复与删除竞争的最终列表与接口结果一致。

- `cd backend && ./mvnw -o -pl examples/demo-service -am install -DskipTests`：通过，先安装当前工作树上游 SDK。
- `cd backend && ./mvnw -o -pl examples/demo-service spotless:apply`：通过。
- 逐片定向运行 `OrderRestoreTest`：新增 9 项全部通过，覆盖已删除分页/排序及元数据、编号和原有状态、审计及通知/待办、真实唯一索引冲突、权限、Data Policy（SELF 与无策略默认拒绝）、跨租户、重复/并发及恢复/删除竞争。
- `cd backend && ./mvnw -o -pl examples/demo-service test -Dtest=OrderRestoreTest,OrderApiTest,DataPermissionTest,AuditLogTest,TodoOrderTest,StandardColumnsTest,ArchitectureTest`：25 tests，0 failures/errors/skips。
- `git diff --check`：通过。

本工单未修改前端，页面与 Playwright 由后续页面工单验证；MQ 在测试中关闭，出站消息通过 Outbox 契约验证，未验证真实 Broker 投递。全模块交付回归由集成代理统一运行。


### 集成交付回归修复（2026-11-09）

集成交付回归发现非 Web 的 Spring 上下文无法启动：本工单给 `OrderService` 新增了必需的 `CurrentActorProvider` 构造参数，但 SDK 的认证自动配置只在 Servlet Web 应用注册该 Bean。现有 `SchemaCommentTest`、`DatabaseMigrationTest`、`TenantInitializationTest`、`OutboxMessagingTest` 因此在执行测试前失败。

沿用 SDK 标准列填充处理器的可选依赖约定，`OrderService` 改为注入 `ObjectProvider<CurrentActorProvider>`。恢复时缺少 provider 或没有认证上下文均写入 `updated_by = NULL`，表示系统操作；有认证 HTTP 请求继续使用当前已验证 JWT 的操作人。源码仅调整订单服务，不新增全局 Bean，不改变安全配置。

本轮继续使用已确认的 Spring 公开服务启动、现有集成测试与 HTTP 边界，复用能捕获本次回归的现有测试：

- Red：`cd backend && ./mvnw -o -pl examples/demo-service test -Dtest=SchemaCommentTest`，1 test / 1 error，明确报错为 `OrderService` 构造参数 5 缺少 `CurrentActorProvider` Bean。
- 先执行 `cd backend && ./mvnw -o -pl examples/demo-service -am install -DskipTests`：通过，安装当前集成版本的上游 SDK。
- Green：`cd backend && ./mvnw -o -pl examples/demo-service test -Dtest=SchemaCommentTest,DatabaseMigrationTest,TenantInitializationTest,OutboxMessagingTest,OrderRestoreTest`：18 tests，0 failures/errors/skips。四个原失败类恢复通过；`OrderRestoreTest` 的 9 项 HTTP 用例仍通过，包含恢复后的 `updatedBy` 与审计操作人。`OutboxMessagingTest` 的 4 项真实 Broker 用例也通过，补充验证了消费租户隔离、事务回滚、重复投递及 Broker 故障恢复。
- `cd backend && ./mvnw -o -pl examples/demo-service spotless:check` 与 `git diff --check`：通过。

本轮按集成回归的失败范围重跑上述测试，未重复执行模块全量；全量结果与修复后的失败类重跑由集成代理统一汇总。
