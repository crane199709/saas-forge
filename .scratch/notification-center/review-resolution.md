# Notification Center 审查处理

Status: resolved

## 原始结论与范围

审查基线：`efb200f8eb5831b179c6cf7a004f71122e31bc15...255a759d9792007af11104d96cd397cec718990a`，集成分支 `codex/notification-center-integration`。修复在独立工作树、分支 `codex/notification-review-fixes` 上进行。

- Standards：2 项硬性违规，均为 P3；另有 1 项可选 `possible Duplicated Code` judgement。
- Spec：0 项可操作问题。按通知、公告与待办规格、issues 01–05 和 ADR-0022 复核，未发现有代码证据的缺失、部分实现、范围膨胀或实现错误。

原始报告：`/private/tmp/notification-center-review-standards.md`、`/private/tmp/notification-center-review-spec.md`。本记录处理审查结果，不替代原始全量验收证据。

## 必修处理

1. `AnnouncementMapper` 与 `TodoMapper` 的接口 Javadoc 统一声明“所有方法均不受 Data Policy 过滤”，各方法原有用途说明保留。实际依据为 `DataPermissionAutoConfiguration` 的启用条件与 IAM 仅使用 ABAC 策略引擎的依赖约定；显式 tenant 条件、RLS 与 Data Policy 分别说明，不把前两者当作后者的声明。
2. 原有 record 直接重命名：`AnnouncementView` → `AnnouncementResponse`、`PlatformAnnouncementView` → `PlatformAnnouncementResponse`、公告 Controller 中的 `UnreadCount` → `UnreadCountResponse`。同步 Controller、Service、Mapper 的 Java 引用和 XML 两个 `resultType`，不新增镜像模型。核对所有本次新增公告、通知及待办 Controller 后，其直接响应模型均为 `XxxResponse`；`PlatformAnnouncementRow` 仍是内部 Mapper 查询投影，不直接返回 HTTP。

另澄清 `validation-05.md` 中“总31.0秒”：该数字为 Playwright 测试阶段耗时，不含构建和服务启动；整轮 runner 总耗时未记录，本轮不推算该值。

## 可选建议的取舍

两 App 的 `linkValid` 与 `status` 确有重复。检查两 App 依赖、`frontend/packages/` 目录及 SDK/UI 现有导出后，没有现成的公告公共模块：SDK 提供认证、访问与 API 能力，UI 提供通用组件，均没有这两个公告纯函数的现成归属。

本项是可选 judgement。为两个短函数新增公共 SDK/API，或跨 App 引入领域代码，都会扩大本次范围；依照仓库“仅修改当前需求所必需的内容、最简单可行方案、不引入过度抽象”的规则，保留两端独立实现。前端无改动，不重复已通过的浏览器验收。

## 契约不变的证据

- 对 8 个受影响生产源文件执行 Python 精确比对：从基线读取原文，仅替换批准的类型名及两个 Javadoc 后，与实际文件逐字相等。通过结果为 `PASS exact authorized transformation` × 8；record components、执行表达式、HTTP annotations 和 SQL 正文均不变。
- 公告响应字段保留 `id/source/title/body/subApp/route/expiresAt/publishedAt/revoked/read`；平台管理响应保留 `id/source/title/body/subApp/route/expiresAt/publishedAt/revoked/allTenants/tenantIds`；未读响应保留 `count`。
- `rg -n 'AnnouncementView|PlatformAnnouncementView|\bUnreadCount\b' backend/forge-iam/src` 无匹配；公告 XML 的 `visible`、`tenantList` 均指向新类型。SQL 列与别名、JSON 字段/值、HTTP 路由/状态、权限检查及事件定义未改动。
- 沿用已确认的 HTTP 集成测试边界验证序列化和 MyBatis 映射；本轮属于审查后的文档和类型重命名，不新增镜像测试。

## 实际验证

以下 Maven 命令均在 `backend/` 执行，使用 `JAVA_HOME=/Users/liuhuan/.local/share/mise/installs/java/oracle-21.0.10`，并将其 `bin` 加入 `PATH`。

| 命令 | 结果 | 日志 |
| --- | --- | --- |
| `./mvnw -o -pl forge-iam spotless:apply` | PASS；没有额外格式化改动 | `/private/tmp/notification-review-spotless-apply.log` |
| `./mvnw -o -pl forge-iam -am install -DskipTests` | PASS；18 个 reactor 模块，26.200 秒；安装结束后释放共享 m2 写锁 | `/private/tmp/notification-review-install.log` |
| `./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest,NotificationInboxTest,ArchitectureTest` | PASS；17/17，失败/错误/跳过均为 0，34.274 秒（公告 6、收件箱 10、架构 1） | `/private/tmp/notification-review-targeted-test.log` |
| `./mvnw -o -pl forge-iam spotless:check` | PASS；6.450 秒 | `/private/tmp/notification-review-spotless-check.log` |
| `git diff --check`、`git diff --cached --check` | PASS | — |

不重复此前已完成且本轮未受影响的 IAM/Demo/SDK 全量、前端单测、Playwright 与真实 MQ 全链路验收。本轮仅在本机 Testcontainers 环境运行必要定向测试，无生产连接、push、PR 或 main 合并。

## 完成状态

两个必修项均已修复；Spec 轴无需代码修改，可选建议按上述最小范围原则处理。提交前执行 `git merge --no-edit codex/notification-center-integration`，结果为 `Already up to date.`；最新集成 tip 仍为 `255a759d9792007af11104d96cd397cec718990a`，无合并冲突或额外源码变化。
