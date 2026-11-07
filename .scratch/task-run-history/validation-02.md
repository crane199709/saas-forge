# 工单 02 验收记录

日期：2026-11-07。工作树：`task-manual-trigger`；分支：`codex/task-manual-trigger`；基线：`922d3deaf4dda97ea08cacdf6410b779574d4e0a`（工单 01 已集成）。

## 测试边界

沿用用户已确认入口，不另行确认：服务内部 HTTP 与平台 HTTP。SDK HTTP 测试使用真实随机端口与模块 `SharedContainers` Redis，独立 Redis 库；IAM 租户目录替身位于外部 HTTP 边界，资格校验使用 IAM 发布状态键的真实 Redis 查询。平台测试继承 `PlatformIntegrationTest`，复用 PostgreSQL、Redis 和 Spring 上下文；所属服务替身位于服务间 HTTP 边界，审计通过已发布 `AuditEvent` 验证。没有模拟内部执行、锁或运行记录协作者。

无 ops 模块的兼容性使用 `FilteredClassLoader` 隐藏该模块，经 MVC 请求验证 `/internal/ops/tasks` 返回 404，同时推进声明式调度并通过公开 `TaskRunHistory` 观察成功运行。

## 红绿过程

1. SDK 手动触发：真实 HTTP 预期 200、实际 404；最小实现后，立即返回的 `runId` 与运行查询一致，执行体阻塞期间请求已返回，重复手动及定时触发不会产生第二次执行。
2. 单租户资格与重跑：不符合资格的 Tenant 原先实际受理为 200、预期 409；实现同步状态校验和单租户执行后通过。同期目标字段被忽略的实现还会执行整轮 Tenant Task；最初共用历史导致单租户测试读到两条记录，后续为各测试清理独立历史并通过目标 ID、成功数和实际租户上下文验证只执行指定 Tenant。后台执行没有请求认证身份。
3. 问题运行视图：先失败再成功后 `view=problems` 实际返回 2 条、预期 1 条；未知 view 实际 200、预期 400。补齐过滤和输入拒绝后通过，失败 Tenant 的 ID、总数和安全摘要可通过 HTTP 查询，敏感异常消息不会出现。
4. 平台触发与审计：真实 HTTP 预期 200、实际 404；实现后，客户端伪造的 Operator 字段不会传入下游，平台上下文中的 Operator 同时写入请求和 `ops.taskTrigger`/`ops.taskTenantRetry` 审计，审计包含返回的运行 ID。
5. 平台查询与权限：任务列表与 read-only 查询预期 200、实际 404；实现后任务声明、规则、最近/问题运行及失败租户均可查看，read/manage 权限和 Platform Realm 生效，SDK 的 `task.alreadyRunning`/`task.tenantNotEligible` 原码透传并写失败审计，未重复定义错误码。
6. 任务名编码：中文和空格任务名经平台转发预期 200、实际 404；OpsClient 改用 URI 重载避免二次编码后通过。原路径安全校验保持原样，运维通道及 Dead Event 回归通过。

补充验证了定时执行持锁时同步拒绝手动触发、未知任务/缺失 Operator/错误服务令牌拒绝、Global Task 拒绝 Tenant 参数、无历史仍列任务、cron/时区与 ISO Duration 的响应格式。无 ops 模块兼容性是已实现条件注册的补充验证，没有把首次通过称为红灯。

## API 契约（供工单 03）

平台路径前缀：`/ops/services/{service}/tasks`；经网关调用加 `/platform` 前缀。均返回标准 `ApiResponse`。

- `GET /ops/services/{service}/tasks`：`TaskDefinitionResponse[]`，字段 `name`、`type`（`GLOBAL`/`TENANT`）、`cron`（解析后的六段表达式或 null）、`fixedDelay`（ISO-8601 字符串，如 `PT1H`，或 null）、`zone`（cron 配置的时区 ID，如 `Asia/Shanghai`，或 null）、`latestRun`（优先 current，否则 recent 第一条；无历史 null）。
- `GET /ops/services/{service}/tasks/{name}/runs?view=recent|problems`：`TaskRun[]`；默认 recent。未知 view 400 `request.invalid`，未知任务 404 `task.notFound`。
- `POST /ops/services/{service}/tasks/{name}/trigger`：请求 `{}` 或 `{"targetTenantId":"<UUID>"}`；200 `data={"runId":"<UUID>"}`。Operator 从平台可信上下文获取，内部请求附 `operatorId`，后台任务不继承请求身份。
- 持锁：409 `task.alreadyRunning`；目标 Tenant 不符合 trial/active：409 `task.tenantNotEligible`；Global Task 传入目标 Tenant：400 `request.invalid`。
- `TaskRun` 沿用工单 01：含 `failedTenants`（最多 100）和 `failureCount`，不需要额外详情端点。列表/运行查询使用 `platform:ops:read`，触发使用 `platform:ops:manage`。

## 命令与结论

在 `backend` 执行，先安装源码依赖：

```sh
./mvnw -o -pl forge-sdk/forge-sdk-scheduling -am install -DskipTests
./mvnw -o -pl forge-platform -am install -DskipTests
./mvnw -o -pl forge-sdk/forge-sdk-scheduling,forge-platform spotless:apply spotless:check
./mvnw -o -pl forge-sdk/forge-sdk-scheduling test -Dtest=TaskOpsHttpTest,SchedulingWithoutOpsTest,TaskRunHistoryTest,GlobalTaskExecutionTest,TenantTaskExecutionTest,GlobalTaskRegistrationTest,TenantTaskRegistrationTest,ArchitectureTest
./mvnw -o -pl forge-platform test -Dtest=TaskOpsTest,OpsChannelTest,DeadEventOpsTest,ArchitectureTest
```

- SDK 受影响目标及架构：53 项，失败 0、错误 0、跳过 0（18:31:02 +08:00）。其中新 HTTP 测试 6 项，无 ops 模块兼容性 1 项。
- 平台受影响目标及架构：16 项，失败 0、错误 0、跳过 0（18:29:51 +08:00）。其中新任务运维 HTTP 测试 5 项。
- 整理测试夹具和文案后仅复验变化的测试：SDK `TaskOpsHttpTest` 6 项（18:32:19），平台 `TaskOpsTest,ArchitectureTest` 6 项（18:31:57），均通过。测试整理时出现一次缺失 `StreamSupport` 导入的编译失败，补齐导入后的上述复验已通过。
- Spotless 和 `git diff --check` 通过。未新增数据库表、迁移、生产配置或安全边界。
- 本票没有运行整个仓库测试；模块全量留给根集成流程统一交付一次。Playwright 属于工单 03。

本机日志：`/private/tmp/task-manual-{red1,red2,red3,green1,green2,green3,without-ops,sdk-targeted,sdk-final}.log`、`/private/tmp/task-manual-platform-{red1,red2,red3,green1,green2,targeted,final}.log`；最终机器报告位于各模块 `target/surefire-reports/`。
