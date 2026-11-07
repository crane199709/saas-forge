# 05 界面与真实链路验收

Status: resolved

工作树：`/Users/liuhuan/.codex/worktrees/notification-ui/saas-forge-v2`；分支：`codex/notification-ui`；基线 integration `8cb9607572c1d742a5c140b5c0809e16fd69b208`。
实现快照 `b86bad1`；已通过 `997ce6e` 合入 integration RLS 修复 `b5ebe081b7be04eebb30ef84fa0bbd1c108a9dd5`。RLS 由独立负责人修复，本票没有重复修改后端。

## 结果与边界

- Tenant 铃铛合计 Notification 与当前可见 Announcement，60 秒轮询；隐藏暂停、可见立即恢复，卸载/退出清理并忽略旧请求。
- 三列表：Notification/Todo 按标准信封分页；Announcement 按后端数组契约。支持单条已读、全部通知已读、纯文本、站内 Sub-App 路由。Todo 必须回业务服务处理。
- 两端公告管理以独立权限控制导航和路由，支持发布/撤回、可选到期和链接；平台支持全部或指定租户，UUID 目标不依赖额外租户查询权限。
- Demo 订单列表调用真实 `POST /demo/orders/{id}/close`，显示已关闭；Todo 由业务事件关闭，没有前端本地删除待办。
- 可选隔离 MQ 模式见 `deploy/e2e/notification.md`。默认 main-flow/template 入口与配置不变，通知与模板模式互斥；没有新增 CI 强制入口。

用户确认的测试边界：真实客户端公开 HTTP、页面渲染、时间/visibility 生命周期和 Playwright 用户流程。Browser plugin not available，使用项目已有 Playwright/Chromium，无新浏览器依赖。真实模式不拦网络、不注入会话、不预置 Inbox/Todo；业务创建经真实登录后的公开订单 API，实际处理从 Demo 页面关单。

## 红绿切片

命令在本工作树 `frontend` 运行；日志均位于 `/private/tmp/`。

| 切片 | 命令与 RED | GREEN |
| --- | --- | --- |
| 铃铛 | `pnpm --filter @forge/tenant-app test src/notification/NotificationBell.spec.ts`；缺少铃铛 | 2/2：通知+可见公告、60秒、隐藏/恢复、卸载、慢响应、退出清空 |
| 三列表 | `pnpm --filter @forge/tenant-app test src/notification/NotificationCenter.spec.ts`；路由无列表 | 2/2：分页、已读、纯文本、业务跳转、切列表慢响应隔离 |
| 租户公告 | `pnpm --filter @forge/tenant-app test src/announcement/AnnouncementPage.spec.ts`；无管理页面 | 1/1：实际发布、撤回和历史状态 |
| 平台公告 | `pnpm --filter @forge/platform-console test src/announcement/AnnouncementPage.spec.ts`；无管理页面 | 1/1：实际发布、撤回和历史状态 |
| 浏览器业务处理 | `pnpm exec playwright test -c playwright.config.ts notification-center.spec.ts --reporter=list --output=/private/tmp/notification-center-qa/mock-results`；已到 Demo 但无关单按钮 | 最终相关回归23/23；含两种链接跳转断言，新增移动边界后单条复核1/1 |

红绿日志：`notification-ui-red-{1,2,3,4}.log`、`notification-ui-green-{1,2,3,4}.log`、`notification-ui-bell-lifecycle.log`、`notification-ui-center-stale.log`、`notification-ui-browser-red.log`、`notification-ui-browser-green.log`。

浏览器公开 HTTP 用例覆盖通知/公告已读、全部通知已读、错误 userTip/traceId、重试保留输入、纯文本、三种列表、两种链接形态、真实业务关闭请求、两端无权限隐藏和直接路径403、指定租户 UUID 校验、桌面和窄屏。

## 统一前端质量门禁

- 完整 `pnpm typecheck` 成功，含全部 workspace 与 E2E/tools；日志 `notification-ui-final-typecheck.log`。最后配置/夹具变化后 `pnpm typecheck:tools` 再次成功，日志 `notification-ui-final-tools-typecheck.log`。
- 前端各模块单测最终累计 **250/250**：UI 15、Sub-App 工具6、Platform66、Tenant163。完整 `pnpm test` 的已通过模块不重复运行；修正退出时路由卸载/计数清空的测试时序断言后，只重跑 Tenant 全模块并通过163/163。日志 `notification-ui-final-test-rerun.log`、`notification-ui-final-tenant-test.log`。
- 完整 `pnpm format:check` 成功，日志 `notification-ui-final-format.log`；最后变更文件再次 `prettier --check` 成功，日志 `notification-ui-final-changed-format.log`。
- 实际受影响构建：`pnpm build:spike`（Tenant、Demo v1/v2）与 `pnpm --filter @forge/platform-console build` 全部成功，包含最后模板具名处理函数和窄屏顶栏。平台 favicon 修复后再构建成功，日志 `notification-ui-build-platform-final.log`。Demo 首轮新增动作的表格泛型类型问题已改为传递 ID 并成功重建。
- 首轮全测试发现 Prettier 将模板多语句处理器改成无分号换行，Vue parser 拒绝；已改用具名处理函数，后续完整模块测试和构建通过。
- `bash -n deploy/e2e/run.sh`、`docker compose -f deploy/e2e/compose.yml -f deploy/e2e/compose.notification.yml config` 和 `git diff --check` 通过。Compose 输出 `notification-ui-compose-config.yml`。

既有登录、跨标签和 federation 回归首轮发现新增两未读接口缺少 HTTP 夹具，已补空收件箱应答。最终构建后顺序运行：

```sh
pnpm exec playwright test -c playwright.config.ts notification-center.spec.ts tenant-login.spec.ts cross-tab-session.spec.ts federation.spec.ts --timeout=60000 --reporter=list --output=/private/tmp/notification-center-qa/regression-final-results
```

**23/23 PASS（51.1秒）**，日志 `/private/tmp/notification-ui-browser-regression-final.log`。最后只补跑含390px顶栏边界断言的通知用例：`pnpm exec playwright test -c playwright.config.ts notification-center.spec.ts --grep '通知、公告已读' --timeout=60000 --reporter=list --output=/private/tmp/notification-center-qa/mobile-final-results`，**1/1 PASS（用例3.4秒，总9.6秒）**，日志 `notification-ui-mobile-final.log`。所有可见顶栏链接、按钮和 combobox 左右边界均在视口内，不重构既有固定侧栏。

## 真实隔离环境与结果

入口：仓库根目录 `FORGE_NOTIFICATION_E2E=1 deploy/e2e/run.sh`，或 `frontend` 的 `pnpm test:e2e:notification`。

每轮独立 Compose project，PG/Redis/RocketMQ 5.3.2，仅绑定回环地址；不挂 `deploy/data`。Topic 初始化及三消费组 retry=16 回读成功后才启动 Java。IAM、Platform、Demo 显式使用本轮 `127.0.0.1:18081`；受控环境不继承外部数据库目标。专用 Operator/角色仅在此模式 seed。结束只清理本轮容器和卷。

浏览器：Tenant `http://127.0.0.1:15174`、Demo `http://127.0.0.1:15175`、Platform `http://127.0.0.1:15173`；真实视口1440×1000。mock 视口1280×900和390×844。

1. 首轮完成前后端构建、MQ、四服务和 seed，但浏览器测试前失败：多参数 `defineConfig` 拼接 `webServer` 导致重复启动15174。已按本地 Playwright 实现改为单对象 spread，明确替换服务数组；tools typecheck 和 `--list` 确认两条测试。日志 `/private/tmp/notification-center-qa/real-mq-run.log`、`real-mq-01/`。本轮容器/卷清理完成。
2. 第二轮 `FORGE_NOTIFICATION_E2E=1 deploy/e2e/run.sh`：**真实 MQ 订单闭环 PASS（17.0秒）**。创建订单 → Notification/Todo经 SDK/Outbox/RocketMQ 到 IAM → 浏览器显示 → 已读数下降 → Todo跳转Demo → 页面关单 → Todo消失 → B不可见A通知/待办 → 回A保留已读。
3. 第二轮真实公告：租户发布/已读/隔离/撤回，以及平台全部/指定租户投放/撤回的所有功能断言均完成。最后严格控制台检查仅发现 `http://127.0.0.1:15173/favicon.ico` 404，因而整条用例未通过。trace 已定位唯一错误；平台入口已沿用 Tenant 现有的空 favicon 声明并成功重建，随后仅重跑受影响真实公告用例。

第二轮主日志 `/private/tmp/notification-center-qa/real-mq-run-02.log`；四服务、Compose、Topic、构建与 cleanup 日志，以及失败 trace 已复制 `/private/tmp/notification-center-qa/real-mq-02/`，不依赖工作树 ignored 目录。本轮容器/卷清理完成。主机曾有瞬时负载升高，未终止共享应用/服务；不宣称精确环境根因。

第三轮命令：`FORGE_NOTIFICATION_E2E=1 deploy/e2e/run.sh --grep '真实HTTP'`。**真实公告 PASS（用例21.8秒，Playwright 测试阶段31.0秒）**，租户页、管理员页与平台页的 URL/标题/内容、无框架 overlay、console health、实际交互均通过。主日志 `/private/tmp/notification-center-qa/real-http-run-03.log`；最终四服务、Compose、Topic、构建和 cleanup 日志已复制 `/private/tmp/notification-center-qa/real-http-03/`。31.0秒不包含构建与服务启动；未记录整轮 runner 总耗时。runner 退出0，独立 project 容器为0，网络与卷清理完成。没有重复已通过且未受 favicon 影响的 MQ 订单用例。

服务端有 MacOS Netty native DNS 库缺失后回退系统解析器的既有日志；本轮使用回环地址且真实 HTTP/MQ 操作通过，未为此变更后端依赖。

后端本轮只执行真实 runner 的 `./mvnw -B -DskipTests package`，26模块成功；模块全量由 root 统一执行，本票不重复宣称后端单测。

## 图像证据与验收结论

目录 `/private/tmp/notification-center-qa/`：

- `real-mq-notification.png`、`real-mq-todo.png`、`real-mq-order-closed.png`：真实订单通知、待办及业务关闭。
- `real-announcement-tenant.png`、`real-announcement-platform.png`：真实投放可见性与平台管理历史。
- `notification-error.png`、`notification-desktop.png`、`notification-mobile-final.png`、`tenant-announcement.png`、`platform-announcement.png`、`platform-announcement-mobile-form.png`：公开 HTTP 浏览器流程。最终窄屏图为 `notification-mobile-final.png`，旧 `notification-mobile.png` 不作为最终证据。

票05验收完成：真实 MQ 订单闭环与真实公告各有独立通过证据，相关 mock23条与移动边界复核通过，票据已 resolved。

| 渲染检查 | 结果 |
| --- | --- |
| 页面URL与标题、有效内容 | PASS |
| 无框架错误overlay | PASS |
| 控制台健康 | PASS；平台favicon404已修复并真实复测 |
| 桌面与390px截图 | PASS；顶栏控件无横向越界 |
| 交互与业务结果 | PASS；已读、跳转、真实关单、公告投放和撤回 |

限制：仅 Chromium；可选真实 MQ 模式未接入默认 CI。SDK/后端已覆盖的事件乱序、权限撤回细节不在慢浏览器重复。
