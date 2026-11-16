# 02: 模型页视觉基线与端到端验证

**What to build:** 为「AI 模型」页补 Playwright e2e 与视觉基线，并刷新因新增菜单项而失效的全部平台截图。

**Blocked by:** 01

**Status:** resolved

- [x] `frontend/e2e/visual/platform.spec.ts` 增加模型页 mock 数据与截图：列表、新增抽屉、编辑引用型、禁用确认、空态、错误态，各 1440×900 与 1024×768
- [x] 覆写 `__screenshots__` 时用 `--update-snapshots=all`，逐张查看 PNG，走查 1024 宽度下主要信息可读、操作可达
- [x] 新增 1 条 e2e：登录 → 新增模型 → 列表出现 → 禁用确认（HTTP 边界用 `page.route` mock，渲染与路由真实）
- [x] e2e `afterEach` 保持无 `pageerror` 与控制台异常
- [x] `pnpm lint:style`、`pnpm test:visual` 通过；确认既有平台截图的变化可追溯至新增菜单目录项（侧边栏与菜单布局编辑器）

## Comments

## Answer

2026-11-16：补齐 AI 模型页面的 HTTP mock 浏览器流程和视觉基线；验收边界是前端目标契约，真实 ai-platform 联调仍待后端集成。

- 新增 1 条 Playwright e2e，经 Platform Realm 登录后进入 `/ai-models`，新增成功（201）关闭抽屉并刷新列表，取消禁用不发写请求，确认后提交完整元数据及 `DISABLED`，目录刷新后开关关闭。HTTP 边界显式断言 `/api/ai-platform/models`、Bearer Token、新增含 `apiKey`、启停不含凭据；渲染、路由、SDK 与权限均真实运行。
- `afterEach` 检查无 `pageerror`、无 console error/warning、无 Vite/Webpack 错误遮罩；用例检查页面 URL、标题和非空首屏，并留存禁用后的截图。Browser plugin not available，按前端测试技能回退仓库现有 Playwright。
- `platform.spec.ts` 显式拦截独立 `/ai-platform/models` 服务前缀，覆盖列表、新增抽屉、编辑部署引用、禁用确认、空态和带追踪号的错误态，各 1440×900、1024×768，共新增 12 张 PNG。夹具覆盖长名称、长模型标识、四种能力及三种凭据来源；DOM 断言保存/确认可达、密钥不回显、40px 行高及弹窗背景表格滚动位置。
- TDD 首先证明旧 `**/platform/**` mock 无法覆盖 AI 服务前缀（网关 502，列表失败），补独立路由后浏览器又捕获默认开关把数据行撑至 49px；小尺寸仍为 41.375px。仅为模型开关设置 `size="small"`、外框 `height: auto`，恢复 Shell 规定的 40px 行高；未改全局密度或截图容差。
- 用 `--update-snapshots=all` 刷新原 44 张平台 PNG，与新增 12 张一并逐张 `view_image` 走查。原 44 张中，41 张在 x≥220 的内容区域逐像素不变；菜单布局桌面/笔记本正文各 4687 个像素、恢复默认确认背景正文 4646 个像素变化，范围均为 `[277,438,381,607]`，来自新增「AI 模型」节点及后续节点下移。不能将这三张的变化概括为纯侧边栏变化。
- 冻结最终控制台构建（59 个产物文件哈希），连续五轮各 58 项平台测试通过；每轮 56 张 PNG，后四轮全部与首轮 SHA-256 一致，保留首轮基线。仓库外证据目录：`/tmp/forge-model-management/visual/`，含 `round1`—`round5` 原图与哈希、`frozen-build.json`、`original-diff.json`、`five-round-comparison.json`、逐张走查清单与日志。
- 必要生产修复的回归：控制台 20 文件/128 用例、控制台 typecheck、Playwright 工具 typecheck、`pnpm lint:style` 通过；本次三个前端文件的 Prettier 检查与 `git diff --check` 通过。
- TDD 先验证模型列表请求未被旧 `/platform/` glob 覆盖而失败，再加独立 `/ai-platform/models` mock；行高断言又捕获默认开关将数据行撑至 49px，小尺寸仍为 41.375px，因此只对模型开关使用 `size="small"` 和 `height: auto`，收缩外框后数据行恢复统一 40px，未改全局密度。
- 首次固定基线 `--repeat-each=3` 为 173 通过/1 失败：既有 `tenant AI usage 1440` 用例的抽屉正文被自动滚动，28215 个像素出现差异。保留 actual/diff 原图，不改基线或容差；相关六个用例增加抽屉 `transform: none` 检查、查询按钮 `click({ scroll: 'none' })` 及正文 `scrollTop=0` 断言。1440 场景窄回归连续 20/20 通过；再跑平台固定基线三次重复，174/174 通过。首轮 56 张基线哈希保持不变。
- 最终完整 `pnpm test:visual` 一次通过（233/233），覆盖全部 247 张基线；只重写原有平台 44 张并新增模型 12 张，其余 191 张不变。全量脚本重建后的控制台 59 个产物与冻结构建哈希一致。HTTP e2e 最终 1/1 通过，显式验证页面 URL/标题、非空首屏，`afterEach` 无框架错误遮罩、`pageerror` 或控制台 error/warning。
- 原有平台 44 张的逐像素对比：41 张在 x≥220 的主内容区域完全相同；`platform-menu-layout-desktop.png`、`platform-menu-layout-laptop.png` 的正文各变化 4687 像素，`platform-menu-layout-reset.png` 的正文变化 4646 像素，正文差异框均为 `[277,438,381,607]`，对应新增「AI 模型」目录节点和后续节点下移。并非所有变化都只在侧边栏，菜单管理器正文的三张变化已逐张实证确认。
- 首次固定基线 `--repeat-each=3` 捕获既有租户 AI 用量抽屉的一次滚动两态（173/174 通过，失败原图与差异图保留在 `repeat3-output`）。修正该页六个相关用例：等待抽屉过渡结束、已在视口内的查询按钮用 `click({ scroll: 'none' })`、截图前断言抽屉正文 `scrollTop=0`。不重写基线、不调整容差；该 1440 场景随后 20 次窄回归通过。
- 这些接口均由 `page.route` 应答，未验证真实后端加密、凭据迁移、供应商连通性，也未修改后端；真实联调由两个仓库集成后另行验收。
