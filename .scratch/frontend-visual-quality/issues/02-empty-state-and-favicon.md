# 02: 空状态组件与 favicon

**What to build:** 新增统一的空状态组件并替换现存的裸占位，让空页面给出说明与下一步；三个应用各补一个极简 favicon，消掉标签页上的空白图标。见[规格 4.2、4.3](../spec.md#42-空状态)。

**Blocked by:** 01（两者都会改动基线，串行以避免同一批截图被重跑两次）

**Status:** resolved

- [x] `@forge/ui` 新增空状态组件：可选图标、必填标题、可选描述、默认插槽放主操作；不做按场景拆分的变体组件
- [x] 列表组件保留 `emptyText` 入参兼容，内部改为渲染该组件，调用方不需要改
- [x] 替换现存的四处裸 `el-empty`：字典变更历史三处、平台身份面板一处
- [x] 工作台三张卡片（待办、未读通知、最新公告）的空态改用该组件，并给出一个可点的主操作或明确说明为何没有
- [x] 三个应用各补一个极简 SVG favicon：主色块 + 产品名首字母；不做 logo 体系、不做品牌规范、不新建品牌色命名
- [x] `sub-app.md` 的空状态一节补上组件用法与「空数据 / 无结果 / 加载失败」三者的区别；同步 `sub-app-kit` 模板与 `CHANGELOG.md`
- [x] 测试：组件测试覆盖三种入参组合与插槽渲染；视觉测试覆盖至少一处列表空态与工作台空态
- [x] favicon 人工验收：三个应用的标签页图标显示、颜色与页面主色一致，按 2026-11-17 用户确认关闭；不计为自动化覆盖
- [x] 1024 宽度下空态完整可见不裁切
- [x] `pnpm lint:style`、`pnpm -r test`、`pnpm test:visual:update` 通过，基线更新单独成一个提交

## Comments

**2026-11-14 暂停，未完成**

用户要求暂停。分支 `ticket/02-empty-state` 上有 WIP 提交 `1ca09788`，**未合并到集成分支**，工作区干净。

已完成：`EmptyState` 组件（可选图标、必填标题、可选描述、默认插槽）与组件测试；`ListPage` 保留 `emptyText` 并内部渲染该组件；替换 4 处裸 `el-empty`（字典历史 3 处、身份面板 1 处）；工作台三张卡空态改用组件并各给一个入口。应用测试已过（ui 83、tenant 256、platform 102）。

未做：三个 favicon、`sub-app.md` 与 `sub-app-kit` 模板与 `CHANGELOG.md` 同步、视觉基线重跑与基线提交、1024 宽度检查、`lint:style`/`typecheck`/`format:check`/全量验证、合并集成分支。

恢复时注意：`workbench-empty` 与 dictionary-history 相关基线此刻必然失败（组件已换、基线未更新）；基线重跑要连跑两次确认稳定，并留意 `onboarding-mixed-laptop` 的已知抖动（见工单 01 的 Comments），不要为它重写基线。

## Comments

**2026-11-14 恢复并完成**

提交：`74233363` 合并集成分支、`b635f5ae` favicon 与空状态规范、`c095fb8b` 基线（26 张），合并到集成分支为 `cb054f66`。

视觉基线重跑后连跑两次全量：209 passed、209 passed。实现者做对了一件关键的事：`--update-snapshots=all` 会重写全部 209 张，它把 4 张无实质差异的（`onboarding-mixed-laptop`、`onboarding-error-laptop`、`service-credentials-disable-laptop`、`menu-layout-rename-desktop`）还原为原基线，没有把抖动写进基线。

基线实际变 26 张，多于空态本身的 5 张：`navigation-*`、`platform-navigation-*`、`import-confirmation-*`、`import-rollback-*` 的背景含工作台空态或 `ListPage` 订单空态，属预期联动。

未覆盖：favicon 无自动化接缝，已验证 SVG 合法、构建产物含该文件、HTTP 返回 `image/svg+xml`，但**未肉眼确认浏览器标签页**，需人工看一眼。

### 2026-11-17 人工验收收口

按用户本次“完成验收”的确认，勾选三个应用的 favicon 人工验收项，关闭人工提醒。上面的未确认说明保留为初次交付时的历史记录；本次状态收口不新增自动化验证结论。
