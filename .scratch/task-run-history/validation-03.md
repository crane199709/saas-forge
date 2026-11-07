# 工单 03 验收记录

日期：2026-11-07。工作树：`task-run-ui`；分支：`codex/task-run-ui`；基线：`cd84b79c55d5e097109f9269c524c3d04cf8a5f9`（工单 01、02 已集成）。

## 测试边界

沿用用户已确认的公开 UI 与 HTTP 边界。Playwright 用真实 Vue、Element Plus、PlatformClient、菜单与路由，仅在平台 HTTP 边界应答 API 信封；检查授权头、编码后的中文/空格任务名、view 参数、POST 方法与空/目标租户请求体。没有模拟组件内部协作者或测试内部状态。后端真实 Redis、权限和审计验证沿用 [工单 02](validation-02.md)。本票未使用组件实现细节单测替代 Playwright。

Browser plugin not available。使用仓库已有 `pnpm exec playwright test` 与本机 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`；本机没有 bundled Chromium，未安装浏览器依赖。临时配置 `/private/tmp/task-run-ui.playwright.config.ts` 仅启用 platform-console 服务与本地 Chrome，并把所有测试运行产物写至 `/private/tmp/task-run-ui-playwright`。最终浏览器服务为构建产物的 `vite preview`，URL 为 `http://127.0.0.1:5173/task-runs`。

## 红绿过程

1. 查询链路：`/task-runs` 实际显示 404、找不到“任务运行”标题；最小实现后通过任务规则（cron/zone、ISO 固定延迟）、最近状态/未运行、recent/problems 切换与失败租户详情。103 个失败显示 100 条及未列出 3 条。
2. 手动操作：原来找不到“手动触发”按钮；实现后，取消不发送请求，确认前显示服务与任务，409 `task.alreadyRunning` 即使没有 userTip 也显示明确提示和追踪 ID，成功展示异步受理运行 ID，列表刷新为运行中；失败租户重跑只发送目标租户。
3. 服务切换：人为延迟旧服务刷新响应，切至 shipping 后旧 orders 响应覆盖 shipping 列表而失败；增加请求代次隔离后通过。
4. 任务/视图切换：关闭旧任务历史，打开其他任务后旧响应覆盖新历史而失败；增加历史请求代次并在关闭时失效后通过，也验证 problems 慢响应不能覆盖新 recent 视图。
5. 服务故障：已知不可用服务没有提示而失败；实现局部不可用提示后通过，已知不可用服务不查询任务；请求时才发生的 503 显示服务端提示，并能切回正常服务。
6. 窄屏反馈：重跑受理原先只显示在弹窗背后的主列表，当前详情找不到受理提示而失败；把受理提示放在当前查看层后通过，390×844 实际完成单租户确认、受理结果查看及服务切换。

附加回归：read-only 账号可进入页面、查看失败租户，但没有手动触发/重跑按钮；只有 manage 的账号没有入口，直接访问进入 403。相关 App 菜单顺序与全部现有路由守卫用例通过。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 页面身份 | `/task-runs` 与“SaaS Forge 平台控制台”标题匹配 |
| 非空内容 | 任务列表、历史、失败租户与确认弹窗均有真实内容 |
| 框架覆盖层 | 未发现 Vite/Webpack 错误覆盖层 |
| Console | 6 项任务用例均无相关 warning/error 或 pageerror；主动模拟的 409/503 资源错误除外 |
| 桌面证据 | 1280×900 列表、失败租户详情、alreadyRunning 提示 |
| 窄屏交互 | 390×844 单租户确认重跑、受理提示及服务切换通过 |
| 权限 | read 菜单/路由与 manage 操作控制通过 |
| 慢响应 | 服务、任务、recent/problems 切换均不会被旧响应覆盖 |

在 `frontend` 执行：

```sh
pnpm install --offline --frozen-lockfile
pnpm --filter @forge/platform-console test src/App.spec.ts src/router.spec.ts
pnpm --filter @forge/platform-console build
pnpm exec playwright test --config /private/tmp/task-run-ui.playwright.config.ts platform-task-runs.spec.ts platform-dead-events.spec.ts
```

- App/router：2 文件、22 项通过（18:46）。
- platform-console 构建：`vue-tsc --noEmit` 与 Vite production build 通过；初次构建发现两处 Element Plus `DefaultRow` 类型未断言，按已有 DeadEventPanel 惯例修复后通过。Vite 仍有已有共享入口 chunk 超过 500 kB 的提示，本票不调整分包。
- 最终构建产物 Playwright：8 项通过、25.9 秒（本票 6 项，死信相邻回归 2 项）。
- 修改文件 Prettier 和 `git diff --check` 通过。未新增依赖、后端接口、配置或安全边界。
- 按集成交付约定，本票只跑相关目标测试与 platform-console build；模块全量和全仓 typecheck 由根集成流程统一执行一次。

## 截图与剩余限制

截图均在仓库外，已打开检查：

- `/private/tmp/task-run-ui-desktop.png`：规则、latest 和手动操作入口。
- `/private/tmp/task-run-ui-failed-tenants.png`：失败明细 100 条上限与另有 3 条提示。
- `/private/tmp/task-run-ui-already-running.png`：409 冲突提示与追踪 ID。
- `/private/tmp/task-run-ui-mobile-confirm.png`：窄屏单租户确认。
- `/private/tmp/task-run-ui-mobile-detail.png`：窄屏详情和重跑结果所在层。
- `/private/tmp/task-run-ui-mobile-list.png`：窄屏主列表的既有 Shell 限制。

共享 Shell 的固定 220px 侧栏会压缩 390px 窄屏主列表，截图中可见主区文字换行过多；本票未修改共享布局。新历史/详情/确认弹窗使用视口宽度，窄屏单租户操作与服务切换已实际通过。未验证 Safari/Firefox、全部移动设备、真实后端联调或真实线上任务执行；HTTP 业务语义由工单 02 集成测试负责。
