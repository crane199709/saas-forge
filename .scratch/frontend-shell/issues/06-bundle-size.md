# 06: 构建体积

**What to build:** 两个 Shell 的路由页面改为懒加载；platform-console 按需引入 Element Plus，消除构建时的 chunk 体积警告。tenant-app 保持全量引入 Element Plus，因为 Sub-App 依赖 Shell 提供的 Element Plus。见[规格第 8 节](../spec.md#8-构建体积)。

**Blocked by:** 04 platform-console 路由与布局

**Status:** ready-for-agent

- [x] 两个 Shell 的路由页面改为 `component: () => import(...)`
- [x] platform-console 改为按需引入 Element Plus 组件与样式，不再全量 `app.use(ElementPlus)`；中文语言包（`zh-cn`）仍生效
- [x] tenant-app 继续全量引入 Element Plus，在 `main.ts` 注释说明原因（Sub-App 以 `import: false` 共享 Shell 的 Element Plus）
- [x] 验证：platform-console 的 `pnpm build` 不再出现 chunk 体积警告，并在本工单记录改动前后的入口 JS 体积；`federation.spec.ts` 继续通过，证明 Sub-App 的弹层与组件正常
- [x] `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 通过

## 体积记录

platform-console `pnpm build`（Vite 8.3.5，压缩后）：

|        | 入口 JS                       | 入口 CSS                             | chunk 体积警告 |
| ------ | ----------------------------- | ------------------------------------ | -------------- |
| 改动前 | 1,134.05 kB（gzip 362.31 kB） | 362.03 kB                            | 有             |
| 改动后 | 499.92 kB（gzip 172.53 kB）   | 113.52 kB（全部 CSS 合计 178.22 kB） | 无             |

改动后最大的页面 chunk 是 `AuditPage`（112.78 kB，含日期选择器）。入口已贴近 500 kB 阈值，再往 Shell 公共部分加依赖会重新触发警告。

按需引入用 `unplugin-vue-components`（模板中的 `<el-*>`）与 `unplugin-element-plus`（`@forge/ui` 中具名导入的组件补样式）；`vLoading` 指令的样式在 `main.ts` 中手动引入。单元测试中 Node 无法加载 CSS，测试时不引入样式。
