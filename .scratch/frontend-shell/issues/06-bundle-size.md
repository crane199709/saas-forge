# 06: 构建体积

**What to build:** 两个 Shell 的路由页面改为懒加载；platform-console 按需引入 Element Plus，消除构建时的 chunk 体积警告。tenant-app 保持全量引入 Element Plus，因为 Sub-App 依赖 Shell 提供的 Element Plus。见[规格第 8 节](../spec.md#8-构建体积)。

**Blocked by:** 04 platform-console 路由与布局

**Status:** ready-for-agent

- [ ] 两个 Shell 的路由页面改为 `component: () => import(...)`
- [ ] platform-console 改为按需引入 Element Plus 组件与样式，不再全量 `app.use(ElementPlus)`；中文语言包（`zh-cn`）仍生效
- [ ] tenant-app 继续全量引入 Element Plus，在 `main.ts` 注释说明原因（Sub-App 以 `import: false` 共享 Shell 的 Element Plus）
- [ ] 验证：platform-console 的 `pnpm build` 不再出现 chunk 体积警告，并在本工单记录改动前后的入口 JS 体积；`federation.spec.ts` 继续通过，证明 Sub-App 的弹层与组件正常
- [ ] `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 通过
