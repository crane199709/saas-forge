# 05 界面与真实链路验收

Status: claimed

工作树：`/Users/liuhuan/.codex/worktrees/notification-ui/saas-forge-v2`；分支：`codex/notification-ui`；基线 integration `8cb9607572c1d742a5c140b5c0809e16fd69b208`。

## 范围和测试边界

用户确认的公开边界：真实客户端的公开 HTTP + 页面渲染、定时器/visibility 生命周期、Playwright 用户流程。Browser plugin not available，使用项目已有 Playwright/Chromium。无新浏览器依赖。

- Tenant 铃铛合计 Notification 与可见 Announcement，60 秒轮询；隐藏暂停、可见立即恢复，清理事件/定时器，忽略旧请求。
- 三列表：Notification/Todo 按 API 信封分页，Announcement 按 API 数组；已读和全部通知已读、纯文本、站内 Sub-App 路由；不在 IAM 页面直接关闭 Todo。
- 两端公告管理：独立权限控制导航/路由，发布/撤回、可选到期和链接，平台全部/指定租户。
- Demo 订单列表实际 POST `/demo/orders/{id}/close`，订单状态显示已关闭，Todo 由后端业务事件关闭。
- 隔离真实 MQ 可选入口：见 `deploy/e2e/notification.md`，默认 main-flow/template 模式不变。

## 红绿记录

1. `pnpm --filter @forge/tenant-app test src/notification/NotificationBell.spec.ts`：RED 缺铃铛，GREEN 1/1；后补慢响应与退出，共 2/2。日志 `/private/tmp/notification-ui-red-1.log`、`notification-ui-green-1.log`、`notification-ui-bell-lifecycle.log`。
2. `pnpm --filter @forge/tenant-app test src/notification/NotificationCenter.spec.ts`：RED `/notifications` 尚无列表；GREEN 1/1，后补切列表慢响应，共 2/2。日志 `notification-ui-red-2.log`、`notification-ui-green-2.log`、`notification-ui-center-stale.log`。
3. 两端 `src/announcement/AnnouncementPage.spec.ts`：各自 RED 缺发布页面，GREEN 各 1/1，实际通过公开 HTTP 发布、撤回并保留历史。日志 `notification-ui-red-3.log`/`red-4.log`、`green-3.log`/`green-4.log`。
4. Playwright `notification-center.spec.ts`：RED 已能通知/公告已读、跳转 Demo，但没有真实关单按钮；实现后 GREEN 3/3。还覆盖纯文本、错误及 traceId、重试保留输入、两端权限导航/直接路径403、指定租户 UUID 验证和桌面/窄屏。日志 `/private/tmp/notification-ui-browser-red.log`、`notification-ui-browser-green.log`。

## 验证进度

- `bash -n deploy/e2e/run.sh` 与 `docker compose -f deploy/e2e/compose.yml -f deploy/e2e/compose.notification.yml config` 通过；输出 `/private/tmp/notification-ui-compose-config.yml`。
- `pnpm format:check` 全前端通过；`pnpm typecheck` 已运行，见后续最终结论。
- 全前端 `pnpm test` 首轮发现 Prettier 将模板多语句处理器改成无分号换行，导致 Vue parser 拒绝；改为具名处理函数，正在重跑失败门禁。
- `pnpm build:spike` 的 Tenant 构建、Demo `build`/`build:v2` 和 Platform `build` 均成功；Demo 首次新增关闭动作的泛型表格行类型错误已改为传递 ID 并重建成功。
- 尚未执行真实 MQ 跨服务浏览器链路，不以 mock 结果替代；等待最终 RLS 修复集成后执行。
