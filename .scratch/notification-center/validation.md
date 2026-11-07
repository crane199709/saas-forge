# 通知中心集成验收

Status: resolved

集成分支：`codex/notification-center-integration`。基线：`efb200f8eb5831b179c6cf7a004f71122e31bc15`；实现及审查修复汇总提交：`a0de4934298ae0f90ff1f3d820a7403a6b8605bc`。本文件及规格结项为后续文档提交，不改变业务代码。

## 实现结果

- `forge-sdk-notification` 在业务事务内发送 Notification、开启或关闭 Todo，复用 Outbox 与 `forge-notification` Topic。
- IAM 收件箱固定创建时收件快照，支持分页、未读数、单条及全部已读；通知父表和月分区均强制 RLS，按整月保留至少 180 天并自动维护。
- Todo 按服务与业务键幂等，保留关闭先到的状态，严格比较发生时间并保留纳秒精度；读取时应用当前角色、Plan 和 Sub-App 范围。
- 两 Realm 公告分别授权，支持本租户、全部或指定租户投放，到期、撤回、Membership 已读和审计；全部租户投放覆盖生效期内新建租户。
- Tenant 铃铛、通知/公告/待办列表、两端公告管理及 Demo 真实关单已接入。轮询为 60 秒，页面隐藏时暂停；切换租户及慢响应均有隔离验证。
- 新增租户表均接入清理流程；SQL、标准列、中文表注释及 SDK 依赖方向已检查。

## 验证结果

| 范围 | 结果与证据 |
| --- | --- |
| SDK | 20/20，事务、事件契约、内容/目标/链接校验与架构；见 [01](validation-01.md) |
| Demo 模块全量 | 74/74，0 失败/错误/跳过，含真实 RocketMQ Outbox；`/private/tmp/notification-center-demo-full.log` |
| IAM 模块全量 | 211/211，0 失败/错误/跳过，含原有认证授权与本次三项能力；`/private/tmp/notification-center-iam-full.log` |
| 通知 RLS | 13/13，应用账号父/子表隔离、跨租户写入拒绝、事务清理和并发首建；见 [RLS 验收](validation-rls.md) |
| 审查修复复验 | 17/17，公告 6、通知 10、架构 1；字段、SQL 正文、权限与事件契约未变；见 [审查处理](review-resolution.md) |
| 前端全量单测 | 250/250：Tenant 163、Platform 66、UI 15、工具 6；见 [05](validation-05.md) |
| 前端质量与构建 | 全量 typecheck、format 检查，以及 Tenant、Demo v1/v2、Platform 实际构建通过；最后变更另作针对检查 |
| 浏览器回归 | 23/23，登录、跨标签、federation、三列表、两端公告及业务跳转；另有 390px 顶栏边界 1/1 |
| 真实 MQ 订单闭环 | PASS，17.0 秒：登录、创建订单、SDK/Outbox/RocketMQ/IAM、已读、真实关单、A/B 隔离及已读保留 |
| 真实公告流程 | PASS，用例 21.8 秒、Playwright 阶段 31.0 秒：两端发布、全部/指定投放、隔离、已读、撤回和页面健康 |

模块全量命令在当时的集成工作树 `backend/` 下执行：

```sh
./mvnw -o -pl examples/demo-service -am install -DskipTests
./mvnw -o -pl examples/demo-service test
./mvnw -o -pl forge-iam -am install -DskipTests
./mvnw -o -pl forge-iam test
```

全量后仅对实际变更或失败项复验；文档和响应类型名修复没有重复全部测试。逐票记录中的工作树路径是执行当时的位置，实现工作树在交付时归档，外置日志和截图继续保留。

## Standards

初审发现 2 项 P3 必修项：Mapper 缺少 Data Policy 过滤边界说明；三个 HTTP 响应 record 不符合 `XxxResponse` 命名。已在单一独立修复分支完成，Java/XML 引用同步，17 项针对测试通过；原审查者已复核两项关闭。

另有 1 项可选短函数重复建议。依据仓库最小修改范围规则保留两端独立实现，未新增公共 SDK/API；取舍已获复核认可。详见 [审查处理](review-resolution.md)。

## Spec

独立审查对照规格、票据和 ADR-0022，结论为 0 项可操作问题，未发现有证据的需求缺失、范围膨胀或实现错误。

审查结论：Standards 原必修 2 项（最高 P3）均关闭；Spec 0 项，两个轴均无必修遗留。

## 复现与证据

真实隔离链路入口：

```sh
FORGE_NOTIFICATION_E2E=1 deploy/e2e/run.sh
```

运行模式、端口和隔离约束见 [通知端到端模式](../../deploy/e2e/notification.md)。浏览器使用真实登录，没有网络 mock 或 Inbox/Todo 数据种子；测试资源仅属于本轮隔离 Compose project，结束后容器、网络和卷均已清理。

截图与日志在 `/private/tmp/notification-center-qa/`。主要截图为 `real-mq-notification.png`、`real-mq-todo.png`、`real-mq-order-closed.png`、`real-announcement-tenant.png`、`real-announcement-platform.png`、`notification-mobile-final.png`；最终公告日志在 `real-http-03/`。历史失败及修复过程保留在 [05](validation-05.md)，不计为通过结果。

验证范围为 Chromium 桌面与指定窄屏；当前会话未提供 Browser 插件，使用项目 Playwright。真实 MQ 模式是显式可选入口，未接入默认 CI。SDK 未新增自身表，因此不涉及 archetype 表结构复制；本次服务迁移为 IAM 的 V24–V26。
