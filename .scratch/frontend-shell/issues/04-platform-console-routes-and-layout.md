# 04: platform-console 路由与布局

**What to build:** platform-console 从「五个面板堆在一页」改为与 tenant-app 相同的管理后台布局：每个管理面板对应一个路由页面，侧边菜单按 Operator 的平台权限显示，登录、403、404 为独立页面，路由守卫规则与 tenant-app 一致。见[规格第 4 节](../spec.md#4-platform-console-路由)。

**Blocked by:** 01 会话改存 localStorage

**Status:** ready-for-agent

- [x] 引入 `vue-router`，新增路由：`/login`、`/tenants`、`/plans`、`/sub-apps`、`/operators`、`/roles`、`/403` 与 404 兜底（另保留现有平台审计面板为 `/audit`，需 `auditRead`）；所需权限沿用 `access.ts` 的 `permissions`，`/tenants` 需 `tenantRead`，其管理操作仍按 `tenantManage` 控制
- [x] `/` 跳转到第一个有权限的页面；一个都没有时显示现有的 `access.none` 提示
- [x] 顶栏：产品名、用户菜单（显示 Operator 名称，含「退出」）；侧边菜单按权限显示，当前页面高亮
- [x] 面板组件本身不改；原先由 `App.vue` 读取并下发的 `plans`、`subApps` 改为由使用它们的页面自行读取
- [x] 路由守卫：未登录跳 `/login?redirect=<原路径>`，`redirect` 校验同工单 02；缺少权限跳 `/403`；Token 刷新导致权限变化时菜单随之更新，当前页面失去权限时跳 `/403`
- [x] 测试：Vitest 覆盖不同权限下的菜单、`/` 的跳转目标、403 与 `redirect` 回跳；更新 `App.spec.ts` 及受影响的面板测试
- [x] `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 通过
