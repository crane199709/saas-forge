# 01 SDK 与事件契约验证

Status: resolved

工作树：`/Users/liuhuan/.codex/worktrees/notification-sdk/saas-forge-v2`；分支：`codex/notification-sdk`。开始时 HEAD 与 `codex/notification-center-integration` 均为 `efb200f8eb5831b179c6cf7a004f71122e31bc15`。

## 预期结果与范围

仅实现票 01：SDK 发布入口、record 事件契约、内容/链接/目标校验、自动配置、模块与 BOM、Topic、接入指南。复用 `forge-sdk-mq` 的 Outbox，无新增表。公开边界经用户确认：SDK public entry points 的事务及事件契约；使用真实 PostgreSQL 和真实 EventPublisher，不 mock SDK 内部实现，测试 Mapper 只观察 Outbox 这一外部传输边界。

## 红绿垂直切片

命令均在工作树 `backend` 目录执行，使用默认 Java 17、离线 Maven 和 Docker PostgreSQL 17。首轮单模块测试前，持有 root 协调的 Maven 安装锁执行：

```sh
./mvnw -o -pl forge-sdk/forge-sdk-mq,forge-architecture -am install -DskipTests
```

安装成功并释放锁。

1. 事务外发送：`./mvnw -o -pl forge-sdk/forge-sdk-notification test -Dtest=NotificationClientTest`。RED：缺失 NotificationClient/自动配置/目标/链接的编译失败；实现事务入口后 GREEN：1/1。日志 `/private/tmp/notification-sdk-red-1.log`、`notification-sdk-green-1.log`。
2. Notification 内容及提交/回滚事件：同一测试命令。RED：4 用例中超长标题未抛异常，1 failure；实现内容及事件有效性校验后 GREEN：4/4。日志 `notification-sdk-red-2.log`、`notification-sdk-green-2.log`。
3. Todo 时间与业务键：同一测试命令。RED：缺少 openTodo/closeTodo/TodoOpened/TodoClosed 的编译失败；实现后 GREEN：5/5。用例先发布更晚的 close，再发布更早的 open，确认 payload 原样保留发生时间，而非投递时间。日志 `notification-sdk-red-3.log`、`notification-sdk-green-3.log`。
4. 目标二选一：`./mvnw -o -pl forge-sdk/forge-sdk-notification test -Dtest=NotificationClientTest#aTargetMustChooseExactlyOneRecipientMode`。RED：双目标未抛异常；实现不可变目标和二选一校验后，全类 GREEN：6/6。日志 `notification-sdk-red-4.log`、`notification-sdk-green-4.log`。
5. 站内链接：`./mvnw -o -pl forge-sdk/forge-sdk-notification test -Dtest=NotificationClientTest#linksRejectExternalUrlsAndMalformedRoutes`。RED：6 种非法 URL/route 均未拒绝；实现 App Registry 编码与 URI 路由规则后，全类 GREEN：12/12。日志 `notification-sdk-red-5.log`、`notification-sdk-green-5.log`。
6. 来源服务必填：`./mvnw -o -pl forge-sdk/forge-sdk-notification test -Dtest=NotificationClientTest#blankSourceServicePreventsStartup`。RED：空白服务名仍启动；加入启动校验后，补充长度边界、目标不可变、Todo 回滚、无租户/事务拒绝和共享架构检查，GREEN：20/20。日志 `notification-sdk-red-6.log`、`notification-sdk-green-6.log`。

## 最终针对性验证

```sh
./mvnw -o -N spotless:apply
./mvnw -o -pl forge-sdk/forge-sdk-notification spotless:apply test -Dtest=NotificationClientTest,ArchitectureTest
```

最终结果：`BUILD SUCCESS`，20 tests（NotificationClientTest 19、ArchitectureTest 1），0 failures/errors/skips。测试夹具只创建一次专用库内的表，逐例清空事件，避免已存在的异步维护任务与 DROP TABLE 竞争。日志 `/private/tmp/notification-sdk-final.log`。

共享架构检查验证 SDK 依赖方向、包循环、持久层规则；Maven reactor 解析及编译无模块循环。另执行根 POM/BOM 与 SDK 模块 `spotless:check`、`sh -n deploy/rocketmq/create-topics.sh` 和 `git diff --check`，均通过。

未验证：实际 RocketMQ Broker 投递、IAM 消费展开/权限/时序行为、HTTP 和浏览器流程。这些属于票 02/03/05，不以本票的 Outbox 测试代替。

## 后续票契约指针

- Maven：`io.saasforge:forge-sdk-notification`，版本由 `forge-dependencies` 管理。
- 源码根：`backend/forge-sdk/forge-sdk-notification/src/main/java/io/saasforge/sdk/notification/`。
- 发布入口：`NotificationClient.java`。`send(target,title,body,link)` 返回 Notification UUID；`openTodo(businessKey,target,title,body,link,occurredAt)`、`closeTodo(businessKey,occurredAt)`。
- 目标：`NotificationTarget.java`，字段 `membershipIds`（List<UUID>）/`permissionCode`（String）二选一；静态工厂 `memberships(...)`、`permission(...)`。
- 链接：`NotificationLink.java`，字段 `appCode`、`route`；可为 null。
- `NotificationSent.java`：`id,tenantId,service,target,title,body,link,occurredAt`。
- `TodoOpened.java`：`tenantId,service,businessKey,target,title,body,link,occurredAt`。
- `TodoClosed.java`：`tenantId,service,businessKey,occurredAt`。
- Topic：三个事件均有 `TOPIC = forge-notification`，Tag 为 record 简单类名；`occurredAt` 类型 `Instant`，业务 Todo 时间须沿用原发生时间。
- 标题 200、正文 10000、service/businessKey/permissionCode 255 个 Unicode 字符，均非空；appCode 沿用 `[a-z][a-z0-9-]{0,63}`；route 最长 2048 Unicode 字符且以单个 `/` 起始的合法 URI 路由。
- 接入指南：`docs/business-service-guide.md`「通知与待办」。
