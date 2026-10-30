# 05: @forge/ui 沉淀共享组件

**What to build:** 把两个 Shell 中重复的布局、主题与页面组件沉淀到 `@forge/ui`，Shell 与 Sub-App 共用：`AppLayout`、`applyTheme`、`ErrorAlert`、`ResultPage`、`ListPage`。两个 Shell 和 demo 子应用改用这些组件，外观与行为不变。见[规格第 6 节](../spec.md#6-forgeui)。

**Blocked by:** 02 tenant-app 布局与路由守卫, 04 platform-console 路由与布局, saas-forge-mvp 工单 24、25（demo 子应用的订单列表）

**Status:** ready-for-agent

- [x] `AppLayout`：顶栏 + 侧边菜单 + 内容区；菜单项（标题、路径、子项）由 props 传入，顶栏右侧为插槽；两个 Shell 改用
- [x] `applyTheme(color)`：写入主色及 Element Plus 派生色（`--el-color-primary-light-*`、`-dark-2`），逻辑从 tenant-app 迁出；`/__diagnostics` 的主题切换改用它
- [x] `ErrorAlert` 从 tenant-app 迁出，错误码到文案的转换（`error-tip.ts`）留在各应用；platform-console 的错误提示也改用它
- [x] `ResultPage`：两个 Shell 的 403、404、无租户、无权限页改用
- [x] `ListPage`：搜索区插槽、`el-table`、`el-pagination`，分页与 `@forge/sdk` 的 `Page<T>` / `meta.page` 对接；demo 子应用的订单列表改用
- [x] `vue`、`element-plus`、`vue-i18n` 为 peer 依赖，不新增 Module Federation 共享项；组件文案使用 local scope 的 `zh-CN` 语言包，不创建全局 i18n
- [x] 更新 [docs/sub-app.md](../../../docs/sub-app.md)：Sub-App 如何使用 `ListPage` 与主题 token
- [x] 测试：`@forge/ui` 各组件有 Vitest 测试（`ListPage` 覆盖翻页与分页信息展示，`applyTheme` 覆盖派生色）；`federation.spec.ts` 的共享依赖单例检查继续通过
- [ ] `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 通过
