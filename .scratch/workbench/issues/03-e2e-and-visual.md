# 03: 工作台 E2E 与视觉回归

**What to build:** 按 [spec](../spec.md) 的“测试决策”，补一条真实链路 E2E 与首屏视觉基线。

**Blocked by:** 01, 02

**Status:** resolved

- [x] Playwright 覆盖：登录、问候语、三张卡、点击待办跳转、切换租户刷新
- [x] 无权限用户的卡片隐藏
- [x] 空数据与接口失败的展示
- [x] 视觉回归覆盖桌面与窄屏首屏
- [x] 更新受影响的旧首页断言与截图
- [x] 不新增运营端工作台用例

## Comments

### 2026-11-13 实现接手

接缝沿用已确认规格：真实 Playwright 登录 → 工作台三卡 → 待办 Sub-App → 切租户与刷新；mocked Playwright 覆盖权限隐藏、空与单卡错误，视觉检查桌面、1024 和窄屏。Browser plugin not available，使用项目 Playwright。

## Answer

已补真实工作台主流程与 mocked 浏览器关键状态，清理旧首页断言及其专用 demo context 桩；显式请求 `/demo/context` 的 SDK 测试仍保留。未新增运营端工作台测试。

### 实现与隔离

- 真实用例全程不拦截请求、不注入会话：密码登录、选择 A、问候语与三卡、待办进入真实 demo Sub-App、切到 B、确认 A 数据消失、公开接口标记 B 通知已读、手动刷新到空列表与 0 未读。既有数据范围、Tenant Realm、成员即时失效和字典主链路均保留。
- `seed.workbench.sql` 仅由标准 `run.sh` 主链路向本次创建的 PostgreSQL 容器加载，为 A/B 分别设置待办处理人、通知收件人和公告；通知使用当前时间进入本月分区。不进入 MQ、接入、导入回滚或仓库外模板专项，保持这些专项的空收件箱/应用编码前提；未操作开发或共享数据库。
- IP 拒绝浏览器回归改经真实存在的 IAM 安全设置读取边界触发，避免依赖已经移除的首页 demo 请求；模板与通知专项的旧租户断言改为工作台问候语。
- mocked Playwright 在 1440×900、1024×768、390×844 检查 populated / empty / 单卡 error / hidden：列表上限、已读过滤、隐藏不请求、单卡错误不阻塞其他卡、刷新恢复、无横向溢出、刷新控件可见；桌面与 1024 的三个卡片标题均在首屏。
- 窄屏验收先发现 220px 侧栏挤压标题、刷新按钮遮挡，补充断言先红，再仅对租户工作台复用既有 120px 紧凑侧栏与 token 间距，标题操作纵向排列。1024 原单列让公告完全落在首屏之外，首屏断言先红后将单列断点收窄至 900px。未改 SDK 或运营端样式。

### 验证结论（2026-11-13）

- TDD 证据：无 A/B 工作台种子时真实用例因找不到 A 待办红灯；补隔离种子后通过。390 标题可见断言、1024 公告首屏断言各先失败，最小响应式修复后通过。
- `FORGE_E2E_OFFLINE=1 deploy/e2e/run.sh`：5/5 通过（21.6s），日志 `/tmp/workbench-main-green.log`；隔离服务日志目录 `deploy/e2e/run-BdXpIQUp/`，脚本已清理本次容器与卷。
- `pnpm build:spike`、`pnpm --filter @forge/platform-console build` 后运行 `pnpm test:e2e`：65/65 通过（1.8m），日志 `/tmp/workbench-generic-e2e.log`。
- `pnpm --filter @forge/tenant-app test`：39 文件、255 tests 通过；`pnpm --filter @forge/tenant-app typecheck`、`pnpm typecheck:tools`、`pnpm lint:style`、`pnpm format:check`、`bash -n deploy/e2e/run.sh`、`git diff --check` 通过。
- `pnpm test:visual:update --grep '工作台|导航|账号安全' --update-snapshots=all`：本次 19 个用例通过。补充窄屏通知错误卡局部基线后，`pnpm exec playwright test -c playwright.visual.config.ts --grep '工作台|导航|账号安全|onboarding mixed'`：21/21 通过，含本次 19 个用例与两个既有 onboarding mixed；日志 `/tmp/workbench-visual-target-final.log`。
- 完整 `pnpm test:visual`：206 个用例中 201 通过、5 个截图差异；日志 `/tmp/workbench-visual-full.log`。其中运营端 `laptop › onboarding mixed` 的表格水平滚动位置偏移约 13px，当前分支和原基线源码定向复查均通过，未修改其代码或基线。其余四项为既有字典基线缺少“字典项”tab：`desktop › 字典`、`desktop › 字典-新增`、`laptop › 字典`、`laptop › 字典-新增`。
- 将起始 review 基线 `856e56d8309a6625bb939d3337a81bd129a6f048` 的完整 `frontend/` 归档至 `/tmp/workbench-visual-base/`，离线安装构建，执行 `pnpm test:visual --grep 'onboarding mixed|字典$|字典-新增'`：相同 4 个字典截图失败、2 个 onboarding mixed 通过。日志 `/tmp/workbench-visual-base.log`，原始差异截图保留于 `/tmp/workbench-qa/preexisting-visual/`。按范围保留这 4 张无关基线，不将全量视觉声称为全绿。

### 截图走查

Browser plugin not available，采用项目 Chromium Playwright。页面身份、非空、无框架错误覆盖层、控制台与交互均检查；真实工作台无 error/warn，mock 单卡错误仅允许用例预期的 HTTP 503 资源日志。

本次 22 张新增/修改 PNG 均已逐张 `view_image` 查看：12 张工作台状态首屏、1 张窄屏通知错误卡、5 张导航、2 张账号安全首屏、2 张当前会话区域；删除 2 张旧首页截图。检查了对齐、长文本省略、空值、按钮、1024 首屏三类信息、390 可读性及 ID 的完整展示。账号会话夹具使用带真实 UUID 格式 `sub` 的假 JWT，Identity ID 由实际页面解析展示。

交付用截图（仓库外）：`/tmp/workbench-qa/desktop.png`、`laptop.png`、`narrow.png`、`real-desktop.png`、`account-session-desktop.png`、`account-session-laptop.png`、`workbench-error-notifications-narrow.png`。窄屏修复前证据：`/tmp/workbench-qa/narrow-before.png`。验证 URL 为真实链路 `http://127.0.0.1:15174/` → `/apps/demo/overview`，视觉 `http://127.0.0.1:5184/` 与 `/account/security`，通用 E2E `http://127.0.0.1:5174/`。

边界：模板和 MQ 专项只同步旧首页断言、未重新启动专项；此票据未改其生产能力。视觉仅在本机 macOS Chromium 验收，未据此声称其他浏览器或生产环境通过。
