# 02: 模型页视觉基线与端到端验证

**What to build:** 为「AI 模型」页补 Playwright e2e 与视觉基线，并刷新因新增菜单项而失效的全部平台截图。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `frontend/e2e/visual/platform.spec.ts` 增加模型页 mock 数据与截图：列表、新增抽屉、编辑引用型、禁用确认、空态、错误态，各 1440×900 与 1024×768
- [ ] 覆写 `__screenshots__` 时用 `--update-snapshots=all`，逐张 `Read` 走查 1024 宽度下主要信息可读、操作可达
- [ ] 新增 1 条 e2e：登录 → 新增模型 → 列表出现 → 禁用确认（HTTP 边界用 `page.route` mock，渲染与路由真实）
- [ ] e2e `afterEach` 保持无 `pageerror` 与控制台异常
- [ ] `pnpm lint:style`、`pnpm test:visual` 通过；确认既有平台截图的差异只来自侧边栏新增菜单项

## Comments
