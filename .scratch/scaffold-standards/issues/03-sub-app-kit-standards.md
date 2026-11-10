# 03: sub-app-kit 带入规范

**What to build:** `forge-create-sub-app` 生成的工程带上 `AGENTS.md` 与 `docs/standards/sub-app.md`，接入 `lint:style` 与视觉测试，模板页面改为列表原型。见规格第 2、3 节。

**Blocked by:** 01

**Status:** resolved

- [x] sub-app-kit 发布包包含 `docs/standards/sub-app.md`；`create.ts` 在源码模式与发布模式下都把它复制到生成工程，头部写入版本
- [x] 模板新增 `AGENTS.md`：规则摘要、`pnpm dev/lint/lint:style/typecheck/build/test:visual` 命令、指向 `docs/standards/` 的链接
- [x] 新增导出 `@crane199709/sub-app-kit/stylelint`，monorepo `frontend/stylelint.config.mjs` 改为引用它；生成工程加 `lint:style` 并接入 `build`
- [x] 生成工程附 `playwright.visual.config.ts` 与示例用例：假会话 + `page.route` mock，覆盖接近真实长度的数据与空列表；不附基线 PNG
- [x] `HomePage` 改为 `PageHeader → PageToolbar → ListPage` 列表原型，空值 `—`，时间用 `formatDateTime`
- [x] 按 `docs/agents/frontend-design.md` 三步流程实施：页面简报写在本工单 Comments；在 monorepo 内生成一份工程跑示例视觉用例，逐张查看截图并过走查清单
- [x] `create.test.ts` 断言新增文件与脚本存在；`pnpm lint:style`、`pnpm test:visual` 在 monorepo 内仍通过

## Comments

### 页面简报（模板 `HomePage`）

- **谁在用、主任务**：租户内使用该业务应用的成员，浏览订单并按标题找到某条订单。模板只示范结构，业务团队按自己的接口替换路径与列。
- **主操作**：模板不预置新增流程（属业务逻辑），`PageToolbar` 的 `#actions` 插槽留注释说明主操作的位置。左侧放标题关键字筛选（回车或清空时回到第 1 页重新请求，参数 `keyword`）。原「刷新订单」按钮删除：提交筛选即重新请求，出错时 `ErrorAlert` 已有提示，单独的刷新按钮不让页面更好。
- **一眼可见**：标题、金额、创建时间；备注可截断（`show-overflow-tooltip`），空值显示 `—`。金额列右对齐、保留两位小数；时间用 `formatDateTime`。
- **原型**：列表 `PageHeader` → `PageToolbar` → `ListPage`（`el-table` + 右下分页）。

### 设计走查

在 monorepo 内以 `pnpm create:sub-app visual 5199` 生成工程（未提交），`pnpm install` 后运行 `pnpm test:visual:update`，得到 4 张截图（订单列表、空列表 × 1440、1024），逐张用 Read 查看：

- 主任务首屏可见；模板没有主操作按钮，不存在多个实心主按钮。
- 标题、工具栏、表格左边缘对齐，间距来自组件内置的 `--forge-space-*`。
- 表头、时间、金额不折行；最长标题与最长备注在两档宽度下截断，不撑破布局；空备注显示 `—`。
- 1024 下四列均可读。首轮截图中备注列（min-width 200）在 1024 下挤压标题列，改为 160 后重截，标题显示更多内容。
- 没有说明性灰色小字、裸枚举值；空列表显示「暂无订单」与「共 0 条」。

随后在该工程中 `pnpm build`（含 `lint`、`lint:style`、`vue-tsc`）与 `pnpm test:visual` 均通过，再删除生成的工程。

### 实现说明

- 规范文档：源码模式读仓库 `docs/standards/sub-app.md`，发布模式读 `build.mjs` 复制进发布包的 `dist/standards/sub-app.md`；生成时头部加一行 HTML 注释写入平台版本（取 `@crane199709/sub-app-kit` 的发布版本）。
- stylelint 导出把 `postcss-html` 的语法对象直接传给 `customSyntax`，生成工程不依赖 stylelint 按名称解析 `postcss-html`；`stylelint`、`postcss-html` 列为 sub-app-kit 的 peerDependencies。monorepo 根 `package.json` 新增 `@crane199709/sub-app-kit: workspace:*` 以引用该导出。
- `stylelint`、`postcss-html`、`@playwright/test` 的版本与 `@module-federation/vite` 一样取自 sub-app-kit 的 devDependencies，源码模式与发布版本快照共用同一份清单。
- 视觉用例跑在开发宿主（`pnpm dev --host 127.0.0.1`）上，接口全部 mock，不需要网关；`docs/sub-app.md` 同步说明新增文件与命令。
