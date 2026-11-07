# 03: 平台聚合与界面

**What to build:** 按 spec 第 4 节，在 platform-console 增加任务列表、运行历史和失败租户页面。

**Blocked by:** 02, mq-dead-event/04

**Status:** resolved

- [x] 按服务列出任务、调度规则和最近一次运行
- [x] 可分别查看最近运行和最近非成功运行，并说明这里只保留最近的记录
- [x] 可查看失败租户并单独重跑；超过 100 个时显示未列出的数量
- [x] 手动触发前需确认，任务已在运行时展示明确提示
- [x] 服务不可用时给出提示，其他服务可继续查看
- [x] 按权限显示入口和操作，Playwright 覆盖查看和手动触发

## Comments

- 2026-11-07：工单 03 已由 `codex/task-run-ui` 认领；基线 `cd84b79c55d5e097109f9269c524c3d04cf8a5f9`。


## Answer

已在平台控制台新增 `/task-runs`，复用现有运维服务目录、PlatformClient、ListPage、Element Plus 和 i18n。按服务列出任务类型、cron/时区或固定延迟，以及最近运行状态与时间；历史弹窗分别请求 recent/problems，详情展示运行来源、Operator、目标租户、时间、计数、安全摘要与最多 100 个失败租户，显示未列出数量和最近记录留存说明。

手动触发与单租户重跑均需确认；请求只包含目标租户，不传入 Operator。409 `task.alreadyRunning` 提供明确提示及追踪 ID；受理后展示运行 ID，刷新列表与历史，不误报为已完成。read 控制菜单/路由，manage 控制两类操作；不可用服务可提示并切回正常服务。服务、任务及运行视图使用请求代次隔离，旧请求不能覆盖当前列表。

验证：平台 App/router 22 项目标回归通过；platform-console 构建通过；基于构建产物的 Playwright 8 项通过（本票 6 项 + Dead Event 相邻回归 2 项），含 1280×900 桌面、390×844 窄屏实际交互和 console 检查。详细红绿过程、命令、截图与限制见 [validation-03.md](../validation-03.md)。Browser plugin not available，使用已有本地 Chrome，无新增浏览器依赖。共享 Shell 固定侧栏导致窄屏主列表空间不足，已记录并保持本票范围；确认与详情弹窗、单租户重跑和服务切换已实际验证。
