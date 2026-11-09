# 01: 错误码、已删除视图与接入指南

**What to build:** 按 spec 第 2 节，提供 SDK 错误码和前端“已删除”视图，并编写接入指南。

**Blocked by:** —

**Status:** resolved

- [x] SDK 增加 `resource.restoreConflict`、`resource.restoreBlocked` 及其文案
- [x] `ListPage` 配套提供已删除视图和恢复操作，不改变现有用法
- [x] 接入指南说明手写 SQL、锁、冲突检测和审计要求

## Comments

## Answer

已完成 SDK 共享错误、ListPage 可选视图和接入指南；未实现其他工单的资源后端或页面。

### 共享契约

- SDK 枚举：`io.saasforge.sdk.core.api.ResourceErrorCode.RESTORE_CONFLICT`（`resource.restoreConflict`）与 `RESTORE_BLOCKED`（`resource.restoreBlocked`），均为 HTTP 409，使用 `ForgeException` 进入现有响应信封。
- `RESTORE_CONFLICT` 仅表示现有部分唯一索引冲突；不新增名称唯一性，Role 只查编码，Department/Position 允许重名。中文提示先改名或处理冲突记录；`RESTORE_BLOCKED` 表示依赖的关联资源仍被删除，提示先恢复关联资源。基础中文资源和新增英文资源都由 HTTP 请求的 `Accept-Language` 验证。
- `ListPage` 新增 `deleted?: boolean`：不传时保留原有搜索、表格、分页及空列表行为；`false` 显示未删除视图，`true` 显示已删除视图并追加恢复列。
- 事件：`deleted-change(boolean)` 交由页面更新受控属性、重置第 1 页并加载；`restore(row)` 交回完整条目。原有 `page-change(number)` 保持不变。`loading=true` 禁用切换、恢复与分页。权限、确认、HTTP 请求、成功刷新与失败提示仍由页面负责。
- [接入指南](../../../docs/business-service-guide.md#资源恢复) 说明专用 Mapper XML、常规 `NOT deleted` 与恢复专用 `deleted = true`、复用删除锁、实际唯一索引冲突、`updated_*` 和 Audit Event，以及跨租户和并发验证要求。CHANGELOG 已同步。

### TDD 与验证

公开边界沿用规格已确认的后端 HTTP 集成接口、共享组件公开 props/events 与 DOM。按单片 red → green 完成：冲突错误（缺少枚举时编译失败 → HTTP 409 与中文提示通过）、依赖受阻错误（缺少枚举值 → 通过）、英文提示（中文回退导致断言失败 → 英文通过）、视图切换（缺少控件 → 通过）、恢复操作（缺少按钮 → 完整条目事件通过）。

- `cd backend && ./mvnw -o -pl forge-sdk/forge-sdk-web -am install -DskipTests`：通过，安装工作树当前 SDK 后再单独运行模块测试。
- `cd backend && ./mvnw -o -pl forge-sdk/forge-sdk-core,forge-sdk/forge-sdk-web spotless:apply`：通过。
- `cd backend && ./mvnw -o -pl forge-sdk/forge-sdk-web test -Dtest=ErrorEnvelopeTest`：16 tests，0 failures/errors/skips。
- `cd frontend/packages/ui && pnpm exec vitest run src/ListPage.spec.ts`：7 tests 通过，覆盖旧用法、受控视图、完整条目恢复事件和加载中禁用。
- `cd frontend/packages/ui && pnpm typecheck && pnpm build`：均通过。
- `cd frontend/packages/ui && pnpm exec prettier --check src/ListPage.ts src/ListPage.spec.ts src/i18n.ts` 与 `git diff --check`：通过。

此工单没有资源页面或真实恢复操作，资源级跨租户、并发与 Playwright 验证由后续资源及页面工单完成；全模块交付回归由集成代理统一运行。
