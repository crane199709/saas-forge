# 02: 空状态组件与 favicon

**What to build:** 新增统一的空状态组件并替换现存的裸占位，让空页面给出说明与下一步；三个应用各补一个极简 favicon，消掉标签页上的空白图标。见[规格 4.2、4.3](../spec.md#42-空状态)。

**Blocked by:** 01（两者都会改动基线，串行以避免同一批截图被重跑两次）

**Status:** ready-for-agent

- [ ] `@forge/ui` 新增空状态组件：可选图标、必填标题、可选描述、默认插槽放主操作；不做按场景拆分的变体组件
- [ ] 列表组件保留 `emptyText` 入参兼容，内部改为渲染该组件，调用方不需要改
- [ ] 替换现存的四处裸 `el-empty`：字典变更历史三处、平台身份面板一处
- [ ] 工作台三张卡片（待办、未读通知、最新公告）的空态改用该组件，并给出一个可点的主操作或明确说明为何没有
- [ ] 三个应用各补一个极简 SVG favicon：主色块 + 产品名首字母；不做 logo 体系、不做品牌规范、不新建品牌色命名
- [ ] `sub-app.md` 的空状态一节补上组件用法与「空数据 / 无结果 / 加载失败」三者的区别；同步 `sub-app-kit` 模板与 `CHANGELOG.md`
- [ ] 测试：组件测试覆盖三种入参组合与插槽渲染；视觉测试覆盖至少一处列表空态与工作台空态；favicon 无自动化覆盖，人工确认标签页显示正常
- [ ] 1024 宽度下空态完整可见不裁切
- [ ] `pnpm lint:style`、`pnpm -r test`、`pnpm test:visual:update` 通过，基线更新单独成一个提交

## Comments

**2026-11-14 暂停，未完成**

用户要求暂停。分支 `ticket/02-empty-state` 上有 WIP 提交 `1ca09788`，**未合并到集成分支**，工作区干净。

已完成：`EmptyState` 组件（可选图标、必填标题、可选描述、默认插槽）与组件测试；`ListPage` 保留 `emptyText` 并内部渲染该组件；替换 4 处裸 `el-empty`（字典历史 3 处、身份面板 1 处）；工作台三张卡空态改用组件并各给一个入口。应用测试已过（ui 83、tenant 256、platform 102）。

未做：三个 favicon、`sub-app.md` 与 `sub-app-kit` 模板与 `CHANGELOG.md` 同步、视觉基线重跑与基线提交、1024 宽度检查、`lint:style`/`typecheck`/`format:check`/全量验证、合并集成分支。

恢复时注意：`workbench-empty` 与 dictionary-history 相关基线此刻必然失败（组件已换、基线未更新）；基线重跑要连跑两次确认稳定，并留意 `onboarding-mixed-laptop` 的已知抖动（见工单 01 的 Comments），不要为它重写基线。
