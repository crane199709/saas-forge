# 02: 脚手架生成的 Sub-App 继承统一主题

**What to build:** 开发者通过 sub-app-kit 生成 Sub-App 后，独立开发与挂载到 Shell App 两种方式均使用平台统一的靛蓝 + Slate 浅色主题，无需自行复制或配置色板；已有工程的开发者能够依据明确的升级步骤保持相同约定。

**Blocked by:** 01（平台与现有 Sub-App 统一浅色主题）；需要其共享主题实现、组件交互样式与主题规范作为生成工程的验证基准。

**Status:** resolved

- [x] 仓库内和仓库外两种生成方式均使用统一的宿主主题入口，并携带平台对应版本的 Sub-App 页面规范与生成工程说明；不在页面或模板复制一份独立色板。
- [x] 对真实生成工程完成依赖安装、构建与启动验证；独立开发宿主实际呈现 `#4F46E5` 主色及 01 确定的 Slate 文字、背景、边框和填充，不依赖 Shell App 提供样式才显示正确。
- [x] 生成工程独立开发时，实心主按钮的默认、悬停与按下状态均使用共享交互样式，白字对比度至少为 4.5:1；不能只验证主题变量存在而遗漏实际组件状态。
- [x] 将生成工程的联邦产物挂载到 Shell App 后，按钮、文字与页面内容继承宿主主题；通过诊断切色验证 Sub-App 同步变化，中性色保持统一。
- [x] 生成工程遵守只读 Theme Token 的边界，不覆盖平台全局主题；保留现有对修改文档根节点、全局样式与主题 token 的限制，不为换色放宽边界。
- [x] 更新生成流程的自动化验证，覆盖两种生成方式的共享主题使用、新版规范分发与已有的拒绝覆盖行为；必要调整与规范、模板、接入指南及变更日志同步。
- [x] 升级说明分别讲清已部署 Sub-App 读取新版 Shell App 主题，以及独立开发宿主升级配套共享 UI、kit 和规范副本的步骤；明确写死颜色的既有页面需自行调整，不承诺自动修复外部工程。
- [x] 完成真实生成工程的浏览器验证与 1440、1024 两档截图走查，检查独立开发和 Shell App 挂载两种方式，结果记录在工单验收说明中。
- [x] 脚手架相关测试、构建、类型、样式及格式检查通过；最后对完整改动运行一次所需的前端回归与视觉验证，保留其他任务的既有修改。
- [x] 本工单仅在临时验证工程中演示生成、构建与挂载流程，不自动修改、重新生成或发布仓库外已有工程。

## Comments

**2026-11-16：** 需求方确认本工单依赖 01，工单状态表示可由 agent 领取，实际开始须等待 01 完成。已有继承链能够满足验收的部分直接验证复用，不为本工单增加重复机制。


**2026-11-16：审查修复验收（P3）**

- 页面主任务仍为开发者在独立宿主登录并调试业务内容。本次仅将 DevHost 顶栏布局分隔线改读已有 `--el-border-color-light`，匹配规格 `#E2E8F0`；默认输入边框、色板、布局、字体与隔离契约保持原样。
- 按已确认的真实宿主浏览器边界执行红→绿：持久回归加入现有 `e2e/theme.spec.ts`，使用 demo 独立开发入口；1440、1024 两档先因顶栏实际 `rgb(203, 213, 225)` 与规格 `rgb(226, 232, 240)` 不符而失败，最小修复后两项通过。测试覆盖真实页底、内容面、正文、顶栏和表格分隔色，并检查非空内容、无框架 overlay/控制台错误，以及弹窗打开/关闭。
- 将 02 外部临时工程完整复制到 `/private/tmp/forge-platform-theme/03/theme-proof-sub-app/`，保留原 overrides；SDK/UI 继续消费 02 的真实 tarball，kit 从本修复源码重新 build/pack 并实际离线安装，新增包为 `03/tarballs/crane199709-sub-app-kit-0.2.0-SNAPSHOT.tgz`。原 01/02 工程与证据未修改。审计确认外部 `node_modules` 的 dist 入口、单一 SDK/UI 实例，且已安装 kit JS/CSS 与新 tarball 逐字节一致。
- 外部旧包两档颜色红测同样复现，安装新包后两项浏览器通过，真实顶栏计算色为 `rgb(226, 232, 240)`；页底/内容面/文字、Slate200 表格线、Slate300 输入选择器、40px 行密度、无横向溢出，以及主按钮默认/hover/pressed 与白字对比度 6.29/7.32/8.53 均保持通过。Browser plugin not available，使用现有 Chromium Playwright；IAM/订单仍为外部边界 mock，未启动真实后端。
- 相关验证通过：kit 测试 7 项、kit 类型、工具类型、DevHost stylelint、变更源文件格式、kit build/pack、外部类型与联邦构建（含隔离及样式 lint）。逐张查看源码宿主 2 张及外部 7 张截图，共 9 张，1440/1024 下内容与按钮清晰可达、无重叠遮挡。单条 DevHost CSS 消费者修复未触及视觉基线，沿用已通过的完整 492/221 与 01 基线证据，未重复全量更新。
- 全部补充证据位于 `/private/tmp/forge-platform-theme/03/`：`red-devhost-regression.log` / `green-devhost-regression.log`、`red-external-devhost.log` / `green-external-devhost.log`、`devhost-final.json`、`package-audit.json`、`installed-resolutions.json` 与 `verification.md`。临时服务已关闭；无 push、发布或权限边界变化。本工单继续保持 resolved。


## Answer

2026-11-16：实现与必要验收已完成。

- 复用 kit 的 `startDevHost` → UI `applyTheme()` 与共享 `theme.css`，生成业务页不复制色板、不调用主题写入入口。真实发布包验证发现开发宿主只写入 token，页面底色仍透明、普通正文仍为浏览器黑色；仅在 DevHost 的 body、header 与 main 使用既有页底、内容面和 regular 文字 token，保留结构、尺寸与间距。
- 两个生成入口的自动化验证覆盖共享宿主入口、业务代码只读边界、当前 Sub-App 规范原文分发、精确依赖版本及拒绝覆盖且保留原文件。生成 AGENTS 新增明确升级步骤；规范、后端 archetype 配套说明、接入指南和 CHANGELOG 同步。既有隔离 lint 未放宽。
- 升级说明分别要求已部署 Sub-App 验证新版 Shell 主题继承，以及独立开发对齐 SDK/UI/kit 与共享依赖版本、重新安装/构建/启动、从同版本临时工程复制规范并合入说明。写死颜色须自行迁移；没有自动修改、重新生成或发布已有仓库外工程。
- 红绿证据：生成说明缺少明确主题升级步骤时两项 CLI 测试失败，补齐后 kit 7 项通过（`red-generator.log` / `green-generator.log`）；真实外部宿主颜色断言先因 body 背景透明而失败，最小修正并重打包/重装 kit 后，两档浏览器通过（`red-devhost-colors.log` / `browser-devhost-final.log`）。
- 仓库内实际执行 `pnpm create:sub-app theme-workspace 15178`，安装、格式化、隔离 lint、样式 lint、类型及联邦构建通过；临时 app 已保留到证据目录后移除，原锁文件已恢复。仓库外实际消费本轮 SDK/UI/kit 的 `pnpm pack` tarball：仅 dist 发布内容、exports/bin 文件齐全、无 workspace 协议，规范与版本快照正确，最终生成器输出与已安装工程一致。
- 外部工程保留原始精确依赖坐标，临时 `file:` overrides 只指向本轮三个 tarball；realpath 审计确认 UI/kit 与页面消费同一 SDK/UI 实例，未使用源码 alias/link。初次离线安装缺第三方缓存，联网补齐后安装完成；最终 kit 重装使用本地 tarball 离线成功。pnpm 对 file 坐标与 version peer 范围有提示，已通过实际包路径及运行验证排除重复实例。这是本地发布包消费验证，不是公开 registry 发布验证。
- 真实外部工程最终 `format:check`、typecheck、隔离/样式 lint 和 build 通过。Browser plugin not available，使用现有 Chromium Playwright；独立宿主 `http://127.0.0.1:15177/apps/theme-proof/home` 与 Shell `http://127.0.0.1:5174/apps/theme-proof/home` 各在 1440×900、1024×768 通过，共 4 项。会话、IAM、App Registry 副本及订单均 mock，不启动真实后端、不写注册表。
- 两模式均检查 URL/title、非空订单内容、无框架 overlay/加载错误、控制台无 error/warning、无横向溢出、40px 表格密度、真实 Slate200 表格分隔与其余背景/文字/边框/填充。临时主按钮只在验收工程加入，正式模板没有无业务意义的按钮。实际默认/hover/pressed 颜色为 `#4F46E5` / RGB(71,63,206) / RGB(63,56,183)，白字对比度为 6.29 / 7.32 / 8.53。
- Shell 实际请求外部 `remoteEntry.js` 与联邦 chunk；通过菜单进入生成页，返回诊断页切色，再经菜单进入生成页，未刷新。按钮同步为 alternate `#8b5cf6`，Slate 角色色与表格密度保持不变。最终 14 张截图全部走查，长标题在 1024 下省略且主要信息和操作可达，无遮挡；原图在 `final-screenshots/`，带文件名联系表在 `contact-sheets/`。
- 最终检查：stylelint、UI lint（24 文件、0 warning）、全工作区及工具类型、格式检查通过；工作区测试串行执行，UI 124 / 平台 105 / 租户 256 / kit 7，共 492。既有主题与联邦 E2E 18 项通过。标准生产构建的完整视觉回归 221 项通过，未修改 235 张既有基线；01 已完成的五轮与 repeat 663 证据继续有效。曾误用诊断构建的视觉轮次已单独留存并重建标准产物重跑，不作为通过证据。
- 所有临时工程、tarball、日志、脚本、trace、截图和审计均在 `/private/tmp/forge-platform-theme/02/`；主要审计为 `package-audit-final.json` / `installed-resolutions.json`，最终回归为 `final-checks.log` / `final-workspace-tests.log` / `final-federation-theme.log` / `final-visual-regression.log`。无生产发布、权限或安全边界变化。
