# 03 Todo 验证

Status: resolved

工作树：`/Users/liuhuan/.codex/worktrees/notification-todo/saas-forge-v2`；分支：`codex/notification-todo`。开始时 HEAD 为 integration `f504a17`。本票按用户已确认的 SDK 事件消费与 HTTP 集成边界实施 TDD，无 IAM 测试后门。

## 实现范围

- IAM `todo` 功能包：SDK TodoOpened/TodoClosed EventHandler、共享状态索引、当前成员 GET /todos 分页。
- 按 `(tenant_id, service, business_key)` 幂等；只接受严格更晚的 occurredAt。关闭先到也保存墓碑，迟到旧开启不生效，较晚开启可重开；关闭对全部处理人同时生效。
- Permission 目标查询时使用数据库 `RoleService.grantsOf`（包含 Plan 权限限定），Sub-App 使用 `PlanService.subAppsOf`；旧 Redis 授权快照不参与资格判断。
- V26：Todo 标准列、逻辑删除、部分唯一索引、RLS；纯处理人关联表同样有租户 RLS。时间按整秒和纳秒存储，避免 PostgreSQL 时间戳截断破坏 SDK Instant 的严格先后关系。
- TenantDataCleaner：清理处理人关联并逻辑删除本租户 Todo，重复消费无副作用。
- demo-service：复用订单事务，创建时开启；POST /orders/{id}/close 关闭未支付订单；删除与超时关闭同步发 TodoClosed。

## 红绿证据

使用默认 Java 17.0.12、离线 Maven、SharedContainers（真实 PostgreSQL 17 / Redis，IAM 每类独立库并登记 Redis database）。按 root 协调持有共享安装锁执行成功：

```sh
./mvnw -o -pl forge-iam,examples/demo-service -am install -DskipTests
```

下列命令从工作树根执行；日志均在 `/private/tmp/`。

1. `./backend/mvnw -o -f backend/pom.xml -pl forge-iam test -Dtest=TodoTest`：RED 缺少 TodoOpened EventHandler；实现事件至 HTTP 查询、认证路由和 V26 后 GREEN 1/1。覆盖重复开启、同一 Identity 跨租户不可见、非法跨租户目标忽略、分页总数和 size 上限。日志 `notification-todo-red-1.log`、`notification-todo-green-1.log`。
2. 同命令定向 `TodoTest#closeBeforeOpenLeavesATombstoneAndOnlyALaterOpenCanReopenForAllAssignees`：RED 缺少关闭处理器；加入关闭墓碑与严格时间条件后全类 GREEN 2/2。日志 `notification-todo-red-2.log`、`notification-todo-green-2.log`。
3. 定向 `TodoTest#permissionTargetsUseCurrentDatabaseGrantsAndPlanAndSubAppChangesApplyImmediately`：RED Permission 目标未返回；加入读取时资格与分页同条件过滤后全类 GREEN 3/3。测试通过真实角色 HTTP 修改权限并故意保留 Token 旧版本 Redis 快照，覆盖撤权、重授、Plan 权限与 Sub-App 移除/恢复。日志 `notification-todo-red-3.log`、`notification-todo-green-3.log`。
4. 定向 `TodoTest#tenantCleanupRemovesOnlyThatTenantsTodos`：RED 清理后仍可见；接入 TenantDataCleaner 后全类 GREEN 4/4。日志 `notification-todo-red-4.log`、`notification-todo-green-4.log`。
5. 定向 `TodoTest#aStrictlyLaterInstantIsAppliedWithoutLosingNanosecondPrecision`：RED 相差 1ns 的较晚开启被 PostgreSQL 时间戳截断而忽略；保存整秒 + nanos 后全类 GREEN 5/5，并断言响应精确到纳秒、旧目标不覆盖。日志 `notification-todo-red-5.log`、`notification-todo-green-5.log`。
6. `./backend/mvnw -o -f backend/pom.xml -pl examples/demo-service test -Dtest=TodoOrderTest`：RED 创建订单无 TodoOpened；加入 SDK 后 GREEN 1/1。日志 `notification-todo-demo-red-1.log`、`notification-todo-demo-green-1.log`。
7. 定向 `TodoOrderTest#closingAnOrderClosesItsTodoOnlyOnceAndDeletionAlsoClosesTheTodo`：RED POST 关单返回 404；加入真实动作后全类 GREEN 2/2，断言 close 时间晚于 open、重复关单只发一次、删单发关闭。日志 `notification-todo-demo-red-2.log`、`notification-todo-demo-green-2.log`。

8. 定向 `OrderAutoCloseTest#registeredTaskClosesOnlyExpiredUnpaidOrders`：RED 只有删单的 TodoClosed，超时关闭没有；改为条件关单与事件同事务后，`TodoOrderTest,OrderAutoCloseTest` GREEN 4/4，重复任务不重复发送。日志 `notification-todo-demo-red-3.log`、`notification-todo-demo-green-3.log`。

## API 与业务动作契约

- IAM 直连 `GET /todos?page=1&size=20`；网关 `GET /iam/todos`。Tenant Realm Bearer Token；成员须仍为 active，无额外管理权限。
- 标准 ApiResponse，`data` 为当前页数组，`meta.page` 含 number/size/totalElements/totalPages。默认发生时间倒序、ID 倒序，无自定义排序字段；size 默认 20，上限 100，非法参数返回 400。
- 项目字段：`id, service, businessKey, title, body, link, occurredAt`。link 可缺省，存在时为 `{appCode, route}`；occurredAt 为 ISO Instant，保留纳秒。
- UI 不直接关闭 IAM Todo；回到业务服务完成真实业务动作。关单接口：`POST /demo/orders/{id}/close`（demo-service 直连 `/orders/{id}/close`），沿用 `order:update` 权限与订单 Data Policy，成功返回 ApiResponse<OrderResponse>，status=`closed`；重复调用不刷新状态且不再发关闭事件。
- 创建接口：`POST /demo/orders`，字段沿用 title/amount/customerSource。为订单 ownerId（创建者 Membership）开 Todo。service=`demo-service`，businessKey=`order:<订单UUID>`，title=`处理订单 <订单编号>`，body=订单标题，link=`{appCode:"demo",route:"/overview"}`。`/overview` 是已存在的 Sub-App 订单列表入口。
- 删除订单和超时关单同样关闭该键，均和真实状态变更在同一事务提交。Topic/record 形态沿用票 01：`forge-notification`、TodoOpened/TodoClosed。

## 最终验证与边界

已将 integration `da270fe25196922d4f747c5a33398cb26fceab19` 合入本分支，合并提交 `85454dc`。解决共享订单接入、IAM POM、认证 matcher 与 CHANGELOG 冲突，保留 Notification send 与 Todo open 同一创建事务、全部 close 路径、三类 IAM 入口及仅一份通知 SDK 依赖。

合并源码再次持有 root 协调的 m2 锁执行 `./mvnw -o -pl forge-iam,examples/demo-service -am install -DskipTests` 成功后立即释放，日志 `/private/tmp/notification-todo-merged-install.log`。随后在工作树 `backend` 目录并行执行两个只读依赖的定向门禁：

```sh
./mvnw -o -pl forge-iam spotless:check test -Dtest=TodoTest,NotificationInboxTest,AnnouncementTest,ArchitectureTest,SchemaCommentTest
./mvnw -o -pl examples/demo-service spotless:check test -Dtest=TodoOrderTest,NotificationOrderTest,OrderAutoCloseTest,OrderApiTest,TenantIsolationTest,ArchitectureTest,SchemaCommentTest
```

IAM `BUILD SUCCESS`：20 tests，0 failures/errors/skips；demo-service `BUILD SUCCESS`：17 tests，0 failures/errors/skips。两模块 Spotless、架构规则与迁移表/字段中文注释检查均通过，合并后的通知/公告路由和 Notification+Todo 订单双事件无回归。日志分别为 `/private/tmp/notification-todo-final-iam.log`、`/private/tmp/notification-todo-final-demo.log`。`git diff --check` 通过。

真实 RocketMQ Broker 跨进程投递、IAM 消费后浏览器展示和点击关单，交由票 05 的新隔离 E2E 模式验收；本票 HTTP + Outbox 传输边界验证不能替代该全链路。模块全量测试由 root 在集成交付时统一执行，本票不重复跑模块全量。
