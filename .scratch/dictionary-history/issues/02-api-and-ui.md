# 02: 历史查询、diff 与页面

**What to build:** 按 [spec](../spec.md) 的“实现决策”，提供版本列表、单版本与两版 diff 接口，并在现有字典页面增加“变更历史”页签。

**Blocked by:** 01

**Status:** resolved

- [x] 列表接口返回版本号、变更摘要、变更人与时间，按版本倒序
- [x] 单版本接口返回完整覆盖快照；当前值可对照
- [x] diff 接口返回 added、removed、changed 三类
- [x] 独立 Permission Code；只读，不提供回滚
- [x] 字典页面增加页签：版本列表、版本详情、diff 对照
- [x] 测试覆盖：列表、详情、diff、权限拒绝、跨租户隔离

## Comments

## Answer

- 新增历史 metadata 倒序列表、单版本完整租户覆盖快照及任意两版 added/removed/changed 比较；快照与查询时 `current` 生效值、`manifest` 服务声明分别返回，空字段表示当时未覆盖，不用当前声明重建历史。
- 新增独立 `iam:dictionary-history:read`，IAM Manifest 8 → 9，HTTP 三入口按可信 JWT 绑定租户并检查该权限。仅管理权限不能查看历史；仅历史权限可导航到字典页，默认打开历史且无修改入口。普通成员读取全部合并字典的原接口保持可用。
- 在既有字典页面加入字典项/历史页签、版本详情、双版本选择及比较；请求序号阻止旧版本响应覆盖新选择；失败显示错误与追踪号，空列表显示空状态。1024 宽度下把字典选择区排到上方，避免历史主要列裁切。
- 从本次源码真实导出 IAM/Platform/Demo 四份 Manifest 并生成权限目录。导出发现基线已使用的 `order:importRollback` 未录入目录/动作策略，按协调确认最小补录现有动作及理由并同步目录文档，不改变运行授权。

### 验证

- 按片执行 HTTP 列表、详情、diff 与 Vue 历史列表/详情、diff 的 red → green；测试日志在 `/tmp/dictionary02-*.log`。
- `./mvnw -o -pl forge-iam -am install -DskipTests` PASS。
- `./mvnw -o -pl forge-iam test -Dtest=ReferenceDataTest,DictionaryHistoryTest,ArchitectureTest`：24 tests PASS（16 HTTP，7 历史服务，1 架构）；含三入口权限拒绝、历史只读审计员不能修改、跨租户详情/diff 404、正反向和同版本比较。
- 四份实际 Manifest 导出测试 PASS；`catalog.py --skip-export --check` 使用刚导出产物 PASS；权限目录脚本 19 tests PASS。IAM `spotless:check`、`git diff --check` PASS。
- 前端 DictionaryHistory/DictionaryPage/App/router：35 tests PASS；tenant-app typecheck/build、tools typecheck、stylelint、修改文件 Prettier check PASS。
- Browser plugin not available，按仓库 Playwright 执行。环境初缺锁定 Chromium，已安装浏览器；没有修改依赖或 lockfile。1440×900 与 1024×768 的列表、空状态、详情、diff 共 8 项 visual PASS，8 张 PNG 均已逐张查看，修正窄屏布局后全部重新生成并通过像素比对；页面身份、非空、无框架遮罩、console error 与交互断言 PASS。

真实服务端到端链路、IAM 模块全量及最终交付检查由票据 03 / 集成负责人执行；本票不提供删除、回滚或 Parameter 历史，也未 push。
