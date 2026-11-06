# 05: demo-service 自动关闭超时订单

**What to build:** 按 [spec](../spec.md) 第 6 节，demo-service 新增 Tenant Task「自动关闭超时未支付订单」，超时时长读取参数 `order.auto-close`，作为端到端验收。

**Blocked by:** 03, reference-data/03

**Status:** resolved

- [x] `@TenantTask(name = "order.auto-close", cron = ...)` 关闭超过时长的未支付订单，条件更新保证幂等
- [x] 不同租户覆盖不同的 `order.auto-close` 时按各自的值关闭
- [x] 测试覆盖：按租户参数生效、某租户失败不影响其他租户、重复执行无副作用

## Comments

## Answer

已在 demo-service 接入 `forge-sdk-scheduling`，新增 `order.auto-close` Tenant Task，按业务时区每五分钟执行：

- 每个租户读取 `ReferenceData.durationParameter("order.auto-close")`，按自己的超时时长计算截止时间；任务使用可替换的 `Clock`，未配置时取系统 UTC 时钟。
- 订单新增 `status`，新订单和存量订单默认 `unpaid`，任务仅把超过时长且未逻辑删除的未支付订单改为 `closed`；接口返回状态，分页查询同步读取。V11 迁移包含状态约束和中文字段注释，未增加支付接口或 UI。
- XML 条件更新继续由 SDK 注入租户隔离与 Data Policy，任务不自行跨租户扫描、不绕开拦截器。关闭时维护 `updated_at`、`updated_by = NULL`；已关闭行不再命中，因此重复执行不刷新审计列。
- `forge.scheduling.iam-uri/token` 复用 manifest 地址与令牌。现有 IAM 测试夹具补充目录接口，所有完整 Spring 上下文均指向本地 IAM，避免 cron 意外调用真实目录。

验证：

- 按 TDD 先确认订单 API 缺少 `unpaid` 状态的红灯、再确认 Spring 未注册任务的红灯，随后逐片实现；目标测试均已转绿。
- `OrderAutoCloseTest` 通过 Spring 注册的 cron 触发真实 SDK 执行器，使用共享 PostgreSQL/Redis 容器及独立 Redis 库，经订单 HTTP 接口验收：不同租户 15/30/10 分钟阈值、参数 HTTP 503 的租户不阻断后续租户、无认证的系统审计、重复执行无副作用、未到期与恰好到期订单不关闭、已删除行与目录外租户不变。
- 已执行最新源码依赖安装：`./mvnw -o -pl examples/demo-service -am install -DskipTests`。
- 交付前一次完整执行 `./mvnw -o -pl examples/demo-service verify`：52 项测试全部通过，包括订单 API、租户隔离、标准列、迁移、中文注释、架构规则、Outbox 链路与新增任务用例；Spotless 通过。

迁移会把既有订单视为未支付，后续按租户配置阈值自动关闭；本工单未增加支付状态流转。Redis 锁沿用 SDK 的效率保证，条件更新承担幂等正确性。未修改 SDK、未部署、未推送远端。
