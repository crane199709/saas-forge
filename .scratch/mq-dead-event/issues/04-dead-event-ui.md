# 04: 平台控制台死信界面

**What to build:** 在 platform-console 增加按服务查看和处置 Dead Event 的页面。

**Blocked by:** 03

**Status:** resolved

- [x] 按服务切换，可按状态筛选，分页使用 `ListPage`
- [x] 显示主题、Tag、租户、错误、尝试次数和时间，不显示 payload
- [x] 重放和放弃都要求填写原因，失败时展示错误
- [x] 服务不可用时给出提示，其他服务可继续查看
- [x] 按 `platform:ops:read`、`platform:ops:manage` 显示入口和操作按钮
- [x] Playwright 覆盖查看、重放和放弃

## Answer

平台控制台新增 `/dead-events`：按服务与状态用 `ListPage` 分页查看元数据和最新错误，详情显示事件标识、消费组与处置记录，不展示 payload。重放、放弃均须填写原因，业务失败保留输入并展示 userTip/traceId；read 控制入口，manage 控制待处置记录的操作。服务不可用与查询失败不影响切换其他服务，旧服务的慢响应不会覆盖当前列表或加载状态。

## Comments


### 2026-11-07 实现与验证

- 从已含票03的集成提交 `508f6a5` 开始，核对 `OpsServiceStatus`、`DeadEventResponse`、平台 Controller 与03 Comments后实施；前端使用 `/platform/ops/services` 和 `/platform/ops/services/{service}/dead-events`，PENDING/REPLAYED/ABANDONED，page从1开始、size20，沿用SDK的Response Envelope分页和平台客户端。
- 以已确认的页面公共流程/HTTP seam做纵向红绿切片：先观察列表组件缺失、不可用服务标签缺失、重放按钮缺失、详情按钮缺失、菜单/路由缺失的失败，再逐项实现；慢请求切换用例先发现旧初始化finally提前清除当前loading，修复后通过。未mock应用内部模块。
- 定向单测：`pnpm --filter @forge/platform-console exec vitest run src/ops/DeadEventPanel.spec.ts src/router.spec.ts src/App.spec.ts`，3文件27项通过。平台 `build`（含vue-tsc）通过。格式化仅涉及本票文件，`git diff --check`通过。平台全量单测与frontend全量typecheck/format检查留给集成分支统一执行，不重复跑。
- 新增 `e2e/platform-dead-events.spec.ts` 并接入既有Playwright配置的平台preview，正式CI仍用原有chromium。Browser plugin not available；本地bundled Chromium缺失，使用已授权的本机Chrome和repo外临时配置 `/private/tmp/mq-dead-event-04.playwright.config.ts`，未下载浏览器或新增依赖。
- 浏览器验收目标：`http://127.0.0.1:5173/dead-events` → 查看最新详情、服务/状态切换与翻页、原因必填、重放失败保留原因并重试成功、放弃进入终态、不可用目录与503后切回其他服务、read/manage菜单路由与按钮 → 正确列表与反馈。2项Playwright通过；1280×900桌面和390×844窄屏都实际交互，页面标题/非空/无框架覆盖层通过，pageerror和非预期console error/warn为空（刻意模拟的409/503 HTTP资源日志已解释并排除）。
- 桌面列表与完整处置流程通过。390px窄屏的筛选器、详情与原因对话框可操作，但既有共享AppLayout固定220px侧栏严重挤压列表，不能记作移动端布局全部通过；按本票范围不扩改共享Shell。
- 截图均位于repo外：`/tmp/forge-mq-dead-events-desktop.png`、`/tmp/forge-mq-dead-events-replay-failure.png`、`/tmp/forge-mq-dead-events-mobile-list.png`、`/tmp/forge-mq-dead-events-mobile.png`（放弃原因对话框）、`/tmp/forge-mq-dead-events-mobile-detail.png`。截图禁用有限动画并等待列表完成加载。
