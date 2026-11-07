# Notification 强制 RLS 验收修复

Status: resolved

工作树 `/Users/liuhuan/.codex/worktrees/notification-rls/saas-forge-v2`；分支 `codex/notification-rls`；基线与完成前合入的 integration 均为 `8cb9607572c1d742a5c140b5c0809e16fd69b208`（merge 显示 Already up to date）。

## 缺陷与范围

票02原验证证明了显式 SQL 租户条件下的业务隔离，却未覆盖 ADR-0001 的 RLS 兜底。IAM 认证发现例外仅适用于 ADR-0001 列出的认证数据；ADR-0022 仅豁免 Notification 标准列和保留删除规则，不豁免租户隔离。

V25 未在生产或开发常驻库应用，按已确认范围原地修正；未改接口契约、前端、SDK 或其他业务模块。修复包含：

- `notification`、`notification_recipient` 父表和自动创建的每个月分区均启用 ENABLE/FORCE RLS，读策略与 WITH CHECK 均约束 `tenant_id = app.tenant_id`；未绑定或事务结束后为空时拒绝可见数据。
- `NotificationService` 写入、读取、单条/全读、租户清理均在事务中，通过 mapper XML `set_config(..., true)` 绑定可信 `NotificationSent/TenantDeleted.tenantId` 或已验签 `TenantActor.tenantId`；既有显式 SQL 租户条件保留。
- 全局维护仍通过原有迁移账号 SECURITY DEFINER 函数操作通知分区 DDL，无需借用或绕过数据策略。已有公开运维 HTTP 保留期用例验证实际删除路径。
- 保留分区创建 advisory lock 与快路径；并发等待后的目录预查不足以保证 DDL 幂等，因此保留 `CREATE TABLE IF NOT EXISTS`，仅对 `CREATE POLICY` 的 `duplicate_object` 局部幂等处理，不吞其他 DDL 或 RLS 错误。
- 通知测试夹具改为独立事务内按甲、乙租户逐一绑定、清理，事务结束清除上下文；不以超级用户或关闭 RLS 重置数据。

## 边界与验证

沿用已确认的 IAM 事件消费入口与 HTTP 边界，并在既有 Schema 标准门禁检查 RLS 元数据。数据库角色探针参照既有隔离测试标准，全部 SQL 位于测试 mapper XML；不 mock 私有 Service。

所有命令均在该工作树 `backend` 下以离线 Maven、Java 17.0.12 执行，使用 SharedContainers 隔离的 PostgreSQL 17 / Redis 7，不连接常驻数据库或真实 Broker。

依赖安装已与 root 协调持锁完成并释放：

```sh
./mvnw -o -pl forge-iam -am install -DskipTests
```

成功，日志 `/private/tmp/notification-rls-install.log`。

第一片 RED：

```sh
./mvnw -o -pl forge-iam test -Dtest=SchemaCommentTest#notificationParentsAndMonthlyPartitionsRequireForcedTenantRls
```

1项失败，明确列出两张父表及2026_11/12四张子分区共6对象缺少强制 RLS/读写策略。日志 `/private/tmp/notification-rls-red-schema.log`。

第一片 GREEN：

```sh
./mvnw -o -pl forge-iam spotless:apply test -Dtest=SchemaCommentTest,NotificationInboxTest
```

9/9通过（Schema 2、既有通知7），0失败/错误/跳过。覆盖收件快照、Permission展开、分页计数、已读/全读、跨租户、离开/恢复、最终清理和全局分区保留维护。日志 `/private/tmp/notification-rls-green-1.log`。

第二片并发 RED：增加两个事件消费者同时首次创建2026_03月分区的用例，定向门禁13项中12项通过、1项错误，错误为 `relation notification_2026_03 already exists`。此时锁后 `to_regclass` 预查没有避免重复 DDL；底层目录快照机制未单独证明，不将其作为已证事实。日志 `/private/tmp/notification-rls-red-concurrent.log`。

第二片并发 GREEN：

```sh
./mvnw -o -pl forge-iam test -Dtest=NotificationInboxTest#concurrentFirstDeliveriesCanShareANewMonthlyPartition
```

保留 DDL 自身幂等、局部处理策略重复后1/1通过，两条事件均经HTTP可见，创建的所有分区通过RLS元数据门禁。日志 `/private/tmp/notification-rls-green-concurrent.log`。

最终检查：

```sh
./mvnw -o -pl forge-iam spotless:check test -Dtest=NotificationInboxTest,SchemaCommentTest,ArchitectureTest
```

13/13通过（NotificationInboxTest 10、SchemaCommentTest 2、ArchitectureTest 1），0失败/错误/跳过，Spotless检查通过。日志 `/private/tmp/notification-rls-final-targeted.log`。

新增实际数据库边界探针确认角色为无超级用户/BYPASSRLS权限的 `iam_app`；经真实事件写入甲乙数据后，分别直接读父表及2026_02内容/收件子分区，无租户绑定时为空，绑定甲/乙时只见对应行，绑定甲后写入乙被RLS拒绝，事务提交和失败回滚后再次无绑定查询仍为空。

新增公开事件+HTTP用例覆盖事件事务回滚、后续乙租户消费、甲跨租户已读失败导致回滚、乙继续计数/已读，以及甲重投正常落库。未增加私有服务或内部协作mock测试。

本修复未运行IAM模块全量（root在合入后统一运行一次），未重复demo全量、真实MQ或Playwright；这些由主任务负责。此处不将EventHandler或数据库探针当作Broker/浏览器验证。
