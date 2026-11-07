# 02 Notification 收件箱验证

Status: resolved

工作树 `/Users/liuhuan/.codex/worktrees/notification-inbox/saas-forge-v2`；分支 `codex/notification-inbox`；基线 `f504a17732b3b4689376bdff2cb42a25cb2dee52`。

## 接口契约（供票05接入）

全部接口以 Tenant Realm Bearer Token 的 tenant_id、membership_id 为准，要求该 Membership 当前为 active。

- `GET /notifications?page=1&size=20`：统一分页信封，`data` 为通知数组，`meta.page` 为分页信息；按 occurredAt/id 倒序。通知字段：id、service、title、body、occurredAt、readAt、read、link（appCode/route，可为 null）。
- `GET /notifications/unread-count`：`data: {count: number}`，仅计 Notification；公告使用票04独立接口。
- `POST /notifications/{id}/read`：标记一条已读，重复调用不改变 readAt；非当前收件箱的 id 返回404 notification.notFound。
- `POST /notifications/read-all`：当前 Membership 全部已读，返回200信封。
- 网关路径统一加 `/iam` 前缀；已离开的 Membership 返回403 auth.forbidden。

## Demo真实业务触发契约

与票03共用现有订单创建事务。`POST /orders`（网关 `POST /demo/orders`）带 Tenant Token，创建订单后 NotificationClient.send 发给订单 ownerId（创建者 Membership）；系统创建无 owner 时跳过。标题 `订单已创建`，正文 `订单 <number> 已创建`，链接 `{appCode:"demo",route:"/overview"}`。事件 Topic `forge-notification`、Tag `NotificationSent`，来源 service `demo-service`，通知写入Outbox与订单同事务。票03的 `POST /orders/{id}/close`（网关 `/demo/orders/{id}/close`）关闭同一订单Todo。无 IAM 测试入口。

## 验证进度

用户已确认SDK public、IAM事件消费与HTTP集成、Playwright边界；本票按TDD垂直切片，使用IAM SharedContainers每类独立数据库与Redis库。不连接真实MQ，本票通过消费者同一EventHandler入口投递，HTTP观察结果。

- 第一片RED：缺少NotificationSent处理器，NoSuchElementException；GREEN：消费、重复投递、跨Tenant收件过滤、同Identity不同Membership隔离、分页1/1通过。日志 `/private/tmp/notification-inbox-red-1.log`、`notification-inbox-green-1b.log`。
- 第二片RED：未实现未读计数接口；GREEN：单条已读、全读、首次readAt幂等、跨租户写隔离、分页及size上限，2/2通过。日志 `/private/tmp/notification-inbox-red-2.log`、`notification-inbox-green-2.log`。
- 第三片RED：Permission目标没有展开，预期1实际0；GREEN 3/3，完整后续切片见下。

真实RocketMQ跨服务与浏览器验收由票05隔离模式完成，本票不宣称已验证Broker。

## 完整红绿记录与交付检查

命令在工作树 `backend` 目录执行，离线Maven、Java17、Docker PostgreSQL17/Redis7。首轮测试前持有root协调安装锁执行并成功：

```sh
./mvnw -o -pl forge-sdk/forge-sdk-notification,forge-iam,examples/demo-service -am install -DskipTests
```

- Permission片：`./mvnw -o -pl forge-iam test -Dtest=NotificationInboxTest#permissionRecipientsAreFrozenAtCreationEvenWhenTheEventIsRedelivered`，RED预期1实际0；实现当前RoleService有效授权（含Plan）展开后全类GREEN 3/3，日志`notification-inbox-green-3.log`。后续授权/撤回均经公开角色HTTP，旧事件重投不扩收件人。
- 租户清理片：`./mvnw -o -pl forge-iam test '-Dtest=NotificationInboxTest#finalTenantCleanupOnlyRemovesThatTenantsNotifications+leavingMembershipCannotReadOrChangeItsInboxButOtherTenantStaysAccessible'`，RED清理后甲通知仍在；接入TenantDataCleaner后全类GREEN 5/5。两个Tenant清理隔离与离开后的列表/计数/已读/全读均经HTTP断言。日志`notification-inbox-red-4.log`、`notification-inbox-green-4.log`。
- 分区片：`./mvnw -o -pl forge-iam test -Dtest=NotificationInboxTest#monthlyMaintenanceDropsOnlyWhollyExpiredPartitionsAndOldRedeliveryCannotRecreateThem`，RED维护HTTP返回404；实现SDK GlobalTask与维护函数后全类GREEN 6/6。测试控制外部时钟，经已有运维HTTP触发，等待公开Task Run状态SUCCEEDED，再通过收件箱观察：1月记录清理，跨过180天截止日的5月仍保留；进入12月后5月清理；过期消息重投不恢复。日志`notification-inbox-red-5.log`、`notification-inbox-green-5.log`。
- 停用片：`./mvnw -o -pl forge-iam test -Dtest=NotificationInboxTest#suspendedMembershipKeepsItsRecipientSnapshotAndCanReadAfterResuming`，RED恢复后找不到通知；按spec与MembershipService.exists的未离开约定固定收件后全类GREEN 7/7。Permission目标同样不额外排除invited/suspended，访问仍严格active。日志`notification-inbox-red-6.log`、`notification-inbox-green-6.log`。
- Demo片：`./mvnw -o -pl examples/demo-service test -Dtest=NotificationOrderTest`，RED真实订单创建后Outbox的NotificationSent数量为0（`notification-inbox-red-7b.log`）；实现同事务send。链接核实后补RED：预期`/overview`、实际`/orders`（`notification-inbox-red-7c.log`），修正有效路由后GREEN 1/1（`notification-inbox-green-7b.log`）。验证真实HTTP订单创建、收件者/来源/内容/链接及非法金额请求不增加通知。Outbox是预先确认的外部传输边界。

最终针对性检查：

```sh
./mvnw -o -pl forge-iam spotless:apply test -Dtest=NotificationInboxTest,ArchitectureTest,SchemaCommentTest
./mvnw -o -pl examples/demo-service spotless:apply test -Dtest=NotificationOrderTest,OrderApiTest,ArchitectureTest
./mvnw -o -pl examples/demo-service spotless:apply test -Dtest=NotificationOrderTest
```

IAM第一条命令9/9通过，0失败/错误/跳过，日志`/private/tmp/notification-inbox-final-iam.log`。Demo第二条中OrderApiTest 4/4和ArchitectureTest 1/1通过；NotificationOrderTest的一条辅助失败创建断言原先假设空标题受校验，后续权限变动辅助断言也与观察结果不符，未据此改生产验证规则；最终把失败创建案例限定为已核实的BigDecimal反序列化错误；第三条最终1/1通过，日志`/private/tmp/notification-inbox-green-7b.log`。不把中间失败运行计为通过。

实现边界：V25只新增notification与notification_recipient两张非标准分区表，全部中文COMMENT ON；幂等主记录保留首次收件快照，包括空收件集合。每小时GlobalTask预建当前/下月分区，仅删除整月已满180天的分区；租户最终清理按event.tenantId限定两表，不删除任何其他Tenant记录。IAM表按ADR-0001显式租户SQL，不依赖消费线程租户上下文改写。无新增SDK表，因此无需更新archetype表结构。

未运行模块全量（root统一交付执行）；真实MQ与浏览器闭环延后至票05，不能把本票EventHandler/Outbox集成测试当作Broker已验证。

## 合并后确认

实现提交`a188b43`；已将集成分支`8cb431e`合入本分支，合并提交`dd37fc0`，无冲突。共享Tenant Token链同时保留`/notifications/**`与`/announcements/**`。

```sh
./mvnw -o -pl forge-iam spotless:check test -Dtest=NotificationInboxTest,AnnouncementTest,ArchitectureTest
```

结果14/14通过（NotificationInboxTest 7、AnnouncementTest 6、ArchitectureTest 1），0失败/错误/跳过；日志`/private/tmp/notification-inbox-after-merge.log`。此轮也重新验证了最终标准INTERVAL写法的V25与实际分区删除路径。工作树仅包含本票必要实现、验证及已合入的公告改动。
