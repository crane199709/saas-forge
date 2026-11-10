# 03: sub-app-kit 带入规范

**What to build:** `forge-create-sub-app` 生成的工程带上 `AGENTS.md` 与 `docs/standards/sub-app.md`，接入 `lint:style` 与视觉测试，模板页面改为列表原型。见规格第 2、3 节。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] sub-app-kit 发布包包含 `docs/standards/sub-app.md`；`create.ts` 在源码模式与发布模式下都把它复制到生成工程，头部写入版本
- [ ] 模板新增 `AGENTS.md`：规则摘要、`pnpm dev/lint/lint:style/typecheck/build/test:visual` 命令、指向 `docs/standards/` 的链接
- [ ] 新增导出 `@crane199709/sub-app-kit/stylelint`，monorepo `frontend/stylelint.config.mjs` 改为引用它；生成工程加 `lint:style` 并接入 `build`
- [ ] 生成工程附 `playwright.visual.config.ts` 与示例用例：假会话 + `page.route` mock，覆盖接近真实长度的数据与空列表；不附基线 PNG
- [ ] `HomePage` 改为 `PageHeader → PageToolbar → ListPage` 列表原型，空值 `—`，时间用 `formatDateTime`
- [ ] 按 `docs/agents/frontend-design.md` 三步流程实施：页面简报写在本工单 Comments；在 monorepo 内生成一份工程跑示例视觉用例，逐张查看截图并过走查清单
- [ ] `create.test.ts` 断言新增文件与脚本存在；`pnpm lint:style`、`pnpm test:visual` 在 monorepo 内仍通过
