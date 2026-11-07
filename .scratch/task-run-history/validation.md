# 定时任务运行记录集成验收

日期：2026-11-07。集成分支：`codex/task-run-history`；实现 tip：`6f4ae283954b24f89436c08f299b043f94da00db`；审查基线：`a57188e97324e760f5a58885818542fa2bff4ad2`。

## 完成结果

- [工单 01](issues/01-task-run-record.md)：Redis Task Run、失败租户、安全摘要、有界历史、90 天 TTL、崩溃恢复与锁令牌保护。
- [工单 02](issues/02-task-manual-trigger.md)：异步手动触发、单租户重跑、查询接口、权限及平台审计，复用已有内部运维通道。
- [工单 03](issues/03-task-run-ui.md)：控制台 `/task-runs`、运行与问题历史、失败租户明细和确认重跑、权限与请求竞态处理。

三个工单的状态均为 `resolved`。SDK package-info、接入指南与 CHANGELOG 已同步更新；本次没有数据库表或迁移变更。

## 交付验证

| 检查 | 结果 |
| --- | --- |
| 源码依赖与现有调度使用方编译 | 23 个 reactor 模块安装通过，包含平台、IAM、导入导出 SDK 与示例服务 |
| scheduling 模块全量 `verify` | 53 项通过，失败、错误、跳过均为 0 |
| platform 模块全量 `verify` | 35 项通过，失败、错误、跳过均为 0 |
| 审查修复后的目标回归 | 调度 29 项、平台 6 项通过；覆盖本轮改变的资格与提交路径 |
| platform-console 全量测试 | 12 个测试文件、65 项通过 |
| 前端工作区与工具脚本类型检查 | `pnpm typecheck` 通过 |
| 前端格式与全差异空白检查 | `pnpm format:check`、`git diff --check` 通过 |
| 控制台生产构建 | Vue 类型检查与 Vite build 通过 |
| 构建产物 Playwright | 任务页面 6 项、死信相邻回归 2 项通过 |

后端全量检查包含架构与 Spotless。全量检查在交付阶段各执行一次，随后两处审查整理仅复验受影响目标；前端与审查前的构建和浏览器验证使用相同源码。

逐票红绿过程、命令及日志位置见 [01](validation-01.md)、[02](validation-02.md)、[03](validation-03.md)。根集成日志位于 `/private/tmp/task-run-history-{install,backend-verify,frontend-tests,typecheck,format}.log`。

## 双轴审查

- Standards：0 项硬性违规；2 项 P3 重复代码判断已由一个新的实现代理以私有方法合并，提交 `ffc5fb9`，并经目标回归验证。
- Spec：0 项需求遗漏、范围外行为或错误实现。详情见 [审查记录](review.md)。

## 覆盖范围与限制

Browser plugin not available，浏览器验证使用本地 Chrome，覆盖 1280×900 与 390×844 的页面身份、非空内容、框架覆盖层、console、查看及确认触发/重跑。Playwright 在平台 HTTP 边界使用测试响应；真实 Redis/PostgreSQL、权限与审计由后端集成测试覆盖，未执行真实后端联调或生产任务。

390px 窄屏的主列表仍受既有固定侧栏挤压；历史、详情、确认弹窗与单租户重跑已实际验证。本次未修改共享 Shell 布局，未验证 Safari/Firefox。
