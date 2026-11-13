# 03: 字典历史验收

**What to build:** 按 [spec](../spec.md) 完成端到端验收，覆盖真实修改后查看历史与 diff。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 修改字典项后，历史页签出现新版本
- [x] 连续两次修改后，可比较任意两版并看到 added/removed/changed
- [x] 无变化保存不产生版本
- [x] 服务重启或 Manifest 升级不产生租户历史版本
- [x] 前端组件测试 + 一条 Playwright 真实链路
- [x] 审计事件仍独立记录，历史表不替代审计

## Comments

- 2026-11-13：由 `codex/dictionary-history-03` 认领，基于集成 `bb4ae36a`。按已确认的 HTTP 与真实 Playwright 接缝验收；Browser plugin not available，使用仓库 Playwright。

## Answer

- 在既有 `main-flow.spec.ts` 新增一条真实 Playwright 链路，使用实际登录及隔离 IAM / Gateway / demo / platform，不 mock 网络或注入会话：修改后 v1、新增及连续修改后 v1–v3、任意两版 added / changed 与反向 removed、历史快照与当前上下文对照、no-op 不增版。
- HTTP 补验收验证重复启动登记和 Manifest 升级不产生历史、旧完整覆盖快照不被默认值更新；加强原审计用例，同时验证字典四次有效修改的历史与独立 `dictionary.item*` AuditEvent。
- 后端目标类 24 项 PASS；真实 E2E 1 项 PASS；工具 TypeScript、Java Spotless、修改文件 Prettier 和 diff 检查 PASS。1440 / 1024 的真实 UI 截图均已逐张查看，页面身份、非空、无遮挡、console / pageerror 与交互检查 PASS。
- 仅修改验收测试与记录，无生产代码、依赖、锁文件或视觉基线变更。前三轮 E2E 的测试定位器／最终标题竞态已修正，详情、实际命令、日志与验证边界见 [验收报告](../acceptance.md)。
- 集成负责人已完成前端全仓 420 项与全仓 typecheck；IAM 模块全量 `verify` 在 `e4d19bea` 为 339 项通过。审查修复后的定向复验与最终审查结果见下节。


## Standards 审查修复

- 2026-11-13，`codex/dictionary-history-review-fixes` 基于集成 `e4d19bea` 修复三项审查发现：通过身份 service 为历史列表／详情提供姓名，原 ID 仅在有说明提示中；三个公开响应 record 直接改名为 `*Response`；历史快照／当前值状态列使用 `el-tag`。
- 按已确认 HTTP／组件接缝完成姓名与状态标签 red → green；相关后端 25 项、前端 14 项、页面／工具类型、样式、格式与 8 项视觉比对通过。8 张基线逐张走查，六张内容图更新、两张空态不变，依赖锁文件未变。
- 真实 E2E 加强姓名及身份提示断言并通过；真实截图禁用动画后复跑记录见 [验收报告](../acceptance.md)。集成负责人已完成最终双轴复核：Standards 0 项、Spec 0 项；三项初审发现均关闭，修复已集成至 `cb65a169`。
