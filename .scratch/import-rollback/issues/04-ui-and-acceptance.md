# 04: 回滚 UI 与 demo 验收

**What to build:** 按 [spec](../spec.md) 的“实现决策”，`@crane199709/ui` 提供导入历史与回滚对话框，demo-service 提供可验收的覆盖导入链路。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 导入历史列表显示任务状态、回滚状态与快照截止时间
- [x] 回滚对话框先展示冲突报告，再要求管理员确认；支持跳过冲突
- [x] 无快照或快照过期时显示“不可回滚”，不隐藏原因
- [x] demo-service 订单导入提供覆盖场景：导入、修改一条、回滚、验证冲突与跳过
- [x] 前端组件测试覆盖冲突报告、确认、过期状态；Playwright 覆盖真实链路
- [x] 审计与权限在 demo 中可验证

## Comments

## 页面简报

- 使用者是租户内有订单导入权限的成员；页面主任务仍是查询和维护订单，主操作保持新增。导入历史、覆盖导入和回滚是工具栏低频入口。
- 导入历史复用表格原型，显示原导入状态、覆盖/追加模式、回滚状态、快照实际截止时间；无快照/过期给出明确中文或英文原因，全部恢复优先显示已回滚。
- 回滚在独立对话框先预览影响数量与逐条冲突；管理员必须明确确认恢复，冲突时额外勾选跳过，结果展示恢复/跳过/失败并支持重试。独立回滚权限决定执行入口，历史仍可见。
- 已授权测试接缝：Vitest mocked-client 组件、隔离 demo 真实 HTTP 和一条真实 Playwright 链路。Browser plugin not available，使用仓库 Playwright，1440/1024 关键状态截图逐张查看。


## Answer

已完成共享导入历史与回滚对话框，以及 demo 的覆盖导入入口和隔离真实浏览器验收。原订单查询/维护主任务、追加导入与原导入状态保持。

- `@crane199709/ui` 导出 `ImportHistoryDialog`、`ImportRollbackDialog` 和公共两阶段接口类型。历史表格展示本人任务、业务方翻译的导入模式、原导入状态、回滚结果/计数和快照实际截止时间；无快照与过期明确给出不可回滚原因。部分回滚后到期保留历史结果，同时解释剩余记录已过期；全部恢复即使 `snapshotAvailable=false` 仍显示已回滚。公开 props 带文档注释。
- 对话框调用业务方的 GET 预览，显示总覆盖数、当前可恢复数和逐条冲突原因，不展示快照旧值；有冲突时必须勾选明确跳过才能确认。执行返回部分结果可重试，409 后重新 GET 报告并清除旧确认；全部恢复后不再显示过时预览或重复执行入口。原因码均映射中英文文案，不直接露出内部枚举。
- Demo 新增覆盖导入/导入历史低频入口。上传与模板按本次模式选择；确认先读取任务的持久化 `importModel`，再选择追加 `/orders/import/{id}/confirm` 或覆盖 `/orders/import/overwrite/{id}/confirm`，不依赖当前弹窗模式。回滚独立绑定 `order:importRollback`，GET `/orders/import/{id}/rollback-preview`、POST `/orders/import/{id}/rollback` 的 `{skipConflicts}`；服务端继续负责当前租户、本人任务和当前数据权限边界。
- 新增专用 Compose S3 overlay、只给 demo 的 storage overlay、独立导入成员 seed、Playwright config 和 `FORGE_IMPORT_ROLLBACK_E2E=1` runner 分支。仅访问本次容器、保留 env -i、安全配置、Manifest 等待、种子与 owned-resource cleanup；默认在线依赖加 clean 构建，本机显式 `FORGE_E2E_OFFLINE=1` 使用缓存。可重跑入口和覆盖边界已写入 `docs/main-flow-e2e.md`。

验证（Browser plugin not available，使用仓库 Playwright）：

- TDD 已观察首个组件缺失红灯→冲突确认绿灯；部分回滚后过期未解释剩余不可用红灯→修复；全部恢复后仍展示旧可恢复数量红灯→修复。`pnpm --dir frontend/packages/ui exec vitest run src/ImportRollback.spec.ts src/DataIo.spec.ts`：5 个新增 + 6 个原导入导出行为，共 11 项通过，覆盖预览、明确确认、过期、409 重新确认、逐条失败重试、全部成功、历史过期/全恢复与独立入口。日志 `/private/tmp/import-rollback-04-components-final.log`。
- UI/demo 定向类型检查、工具 TypeScript、订单页样式、相关文件 Prettier、`bash -n deploy/e2e/run.sh`、`git diff --check` 通过；最终 Shell/demo 构建通过。没有改后端源码，后端模块全量由集成统一执行。
- 原导入确认 HTTP mock 浏览器回归 5 项通过，日志 `/private/tmp/import-rollback-04-confirmation-regression.log`。1440×900、1024×768 新增 8 张历史/冲突/勾选/部分结果基线，逐张 `view_image` 检查；另比对原导入确认与订单关键状态，相关 18 项视觉用例通过。无裁切、遮挡或按钮折行，长 ID 省略显示、冲突原因可读，实际截止时间可见。日志 `/private/tmp/import-rollback-04-visual-compare-final.log`。新增基线在 `frontend/e2e/visual/__screenshots__/import-rollback-*.png`；既有基线无变化。
- `FORGE_E2E_OFFLINE=1 FORGE_IMPORT_ROLLBACK_E2E=1 bash deploy/e2e/run.sh` 最终 clean 构建真实链路通过（1 条，9.1s）：真实密码登录→浏览器 PUT 有效 xlsx 到独立 S3→预校验→确认覆盖 A/B+C 新增→HTTP 人工修改 A→UI 冲突预览→默认 HTTP 409，A 人工值和 B 导入值均未变→UI 显式 skip→A 保留人工值、B 标题/金额/客户来源恢复旧值且编号/状态/归属不变、C 保留→重复调用幂等。原任务仍为 SUCCEEDED，回滚为 PARTIAL，累计恢复 1/跳过 1/失败 0。日志 `/private/tmp/import-rollback-04-real-final.log`；截图已复制为 `/private/tmp/import-rollback-qa/real-{conflicts-1440,skip-confirm-1440,result-1440,result-1024}.png` 并实际查看。
- 真实独立权限负例使用 `e2e-importer`：access 返回有 order:import、无 order:importRollback，覆盖模板 200，预览和执行 403，界面保留覆盖导入与历史但无回滚执行入口。主流程 URL/title/有效 DOM、无 Vite 错误层、全部 console error/pageerror 收集均通过，无 403/409 错误豁免。
- 审计沿用 03 已通过的真实 HTTP/Outbox 事件证据：独立 io.importRollback、taskId、actor/tenant 和实际计数；本次真实浏览器环境 MQ 关闭，不覆盖 MQ 投递或 IAM 审计列表。跨租户/他人任务/过期执行及中断恢复由 02/03 的真实 HTTP 测试覆盖，本次浏览器不重复扩展。

首轮真实测试因工作簿生成器 4.x 改用 `.toFile()` 而缺文件，修正后同一链路重跑通过；首轮视觉夹具文字定位与 checkbox 点击方式已修正，再完整比较通过。未把初轮失败作为通过证据。
