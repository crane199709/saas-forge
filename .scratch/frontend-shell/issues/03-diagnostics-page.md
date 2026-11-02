# 03: 技术验证诊断收纳到 /__diagnostics

**What to build:** 工单 02（Module Federation 技术验证）留在 tenant-app 正式页面上的诊断元素移到只在开发与 E2E 构建中存在的 `/__diagnostics` 页面。正式构建不包含这些代码与文案，E2E 的全部验证点保留。见[规格第 5 节](../spec.md#5-技术验证诊断收纳)。

**Blocked by:** 02 tenant-app 布局与路由守卫

**Status:** resolved

- [x] 当前路由显示（`shell-route`）、主题切换（`shell-theme`）、scope 探针（`shell-scope`）、共享依赖单例检查（`shared-*`）移到 `/__diagnostics`，`data-testid` 不变
- [x] 该路由只在 `import.meta.env.DEV` 或构建时 `VITE_FORGE_DIAGNOSTICS=true` 时注册；E2E 使用的构建打开该开关
- [x] `spike.*` 文案随之移动或改名为诊断用文案，正式构建产物中不出现
- [x] `federation.spec.ts` 中相关断言改为访问 `/__diagnostics`，原有验证点一个不少
- [x] 验证：不带开关的 `pnpm build` 产物中搜不到诊断组件与文案，访问 `/__diagnostics` 显示 404；`pnpm test:e2e` 通过
- [x] `pnpm typecheck`、`pnpm test`、`pnpm format:check` 通过
