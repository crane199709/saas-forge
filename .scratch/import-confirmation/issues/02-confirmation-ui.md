# 02: 界面与 demo

**What to build:** 更新 demo-service 的导入接口和 `ImportDialog`、`IoTaskList`，让用户完成确认。

**Blocked by:** 01

**Status:** resolved

- [x] demo-service 提供确认接口，并使用与提交时相同的校验和写入逻辑
- [x] 界面显示将写入的行数和确认操作，确认地址由调用方传入
- [x] 非提交人看不到确认操作，过期后明确提示
- [x] 重新校验失败时可下载新的 Error Report
- [x] Playwright 覆盖确认写入和重新校验失败

## Comments

### 页面简报

订单导入提交人先检查将写入的行数，再确认写入。沿用订单列表中的 ImportDialog；待确认任务的行数与确认入口放在最近任务同一行，便于把操作与对应文件关联。上传仅提交校验，模板与错误报告下载为次要操作；非提交人不显示确认，过期显示明确提示。沿用现有弹窗、token 和 Element Plus，不增加页面或重排。测试边界为既定的 demo HTTP、可见组件/浏览器流程和现有真实存储/数据库 fixture。Browser plugin not available，使用普通 Playwright。

## Answer

已实现 demo `POST /orders/import/{taskId}/confirm`（`order:import` 权限，`ApiResponse<IoTask>`），复用提交时的模型和校验函数，确认时通过订单写入函数落库。提交仍仅调用三参 `importFile` 完成校验；现有集成测试的业务成功导入也经 HTTP 确认入口。

`ImportDialog` 由业务调用方传入确认地址和当前 Membership；`IoTaskList` 显示将写入的行数，仅为未过期的提交人任务展示确认入口。待确认释放忙碌状态且不发完成事件，确认响应重新跟踪执行；队列繁忙后保留任务可重试。24 小时以 `heartbeatAt` 为起点，超时与服务端 `io.confirmationExpired` 均显示“确认已过期，请重新上传”。保持待确认轮询并在弹窗重开时刷新；重新校验失败现取新的报告下载票据。原导出自动下载和初次校验失败行为保留。

验收（2026-11-10）：

- TDD：HTTP 确认入口先以 404 失败，新增待写入数量切片先因缺少文案/待确认处理失败，过期切片先因仍展示确认入口失败；逐项实现后转绿。
- `./mvnw -o -pl examples/demo-service -am install -DskipTests` 通过；日志 `/private/tmp/import-confirmation-ui-install.log`。
- `./mvnw -o -pl examples/demo-service test '-Dtest=ObjectStorageTest#httpConfirmation*,ArchitectureTest'`：3 个真实 PostgreSQL/Redis/S3 HTTP 测试 + 1 个架构测试通过，0 失败/跳过。覆盖确认前零写入、确认后可见、重复拒绝、字典变化后重新校验/新报告且零写入、同租户非提交人、跨租户和功能权限。日志 `/private/tmp/import-confirmation-http-tests.log`。
- `pnpm --filter @crane199709/ui exec vitest run src/DataIo.spec.ts`：5/5 通过，含原导出自动下载、初次导入失败、确认后才完成、非提交人/过期、重开刷新。日志 `/private/tmp/import-confirmation-ui-final-unit.log`。
- 聚焦 Playwright：新确认写入、重新校验失败报告、非提交人/过期、队列满重试 4/4，通过普通构建复验；原 storage-purpose 4/4，加原 federation 导入/导出综合回归 1/1，通过临时 518x 端口映射运行，未复用或终止用户 517x 服务。日志 `/private/tmp/import-confirmation-browser-final.log`、`/private/tmp/import-confirmation-browser.log`、`/private/tmp/import-confirmation-regression.log`。临时配置 `/private/tmp/import-confirmation-playwright.config.mts` 与 `/private/tmp/import-confirmation-regressions.config.mts`；后者只重映射原用例端口，原源码未改。
- UI/demo typecheck、tools typecheck、stylelint、改动文件 Prettier 与 `git diff --check` 通过；日志 `/private/tmp/import-confirmation-typecheck.log`、`/private/tmp/import-confirmation-tools-typecheck.log`、`/private/tmp/import-confirmation-stylelint.log`、`/private/tmp/import-confirmation-prettier-check.log`。构建通过，日志 `/private/tmp/import-confirmation-ui-build.log`。

设计走查：Browser plugin not available，按技能使用普通 Playwright。环境 `http://127.0.0.1:5184/apps/demo/overview`，remote 5185；1440×900、1024×768。新增 `import-confirmation-{pending,expired,revalidation}-{desktop,laptop}.png` 六张 macOS 基线，分别覆盖大行数/提交人与他人、超时与已过期、长失败原因/空原因/新报告入口；已逐张用 `view_image` 检查。长原因独立成行，操作间距用现有 token，两个尺寸下按钮可达、文字不裁切、不露内部状态值。`pnpm exec playwright test -c playwright.visual.config.ts import-confirmation.spec.ts --update-snapshots=all` 6/6；`pnpm test:visual import-confirmation.spec.ts` 比对 6/6；日志 `/private/tmp/import-confirmation-visual-update.log`、`/private/tmp/import-confirmation-visual-check.log`。

| 浏览器检查 | 结论 |
| --- | --- |
| 页面地址/标题、实际子应用内容与非空订单区 | 通过 |
| 无框架错误遮罩、无相关 page/console 错误 | 通过（预期队列拒绝 503 已明确允许） |
| 截图证据 | 六张两档基线逐张走查通过 |
| 交互链路 | 上传→待确认→确认→订单刷新；重校验失败→新报告；过期/归属隐藏；队列拒绝→重试均通过 |

限制：浏览器用 HTTP mock 验证真实组件交互，数据库写入与重新校验由真实存储 HTTP 集成测试覆盖；未跑已部署后端的浏览器链路。最终 demo 模块全量和全前端验证由集成分支统一执行。
