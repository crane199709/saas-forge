# 02: tenant-app 布局与路由守卫

**What to build:** tenant-app 改为常规管理后台布局：顶栏放租户切换与用户菜单，侧边菜单列出内置管理页与后端下发的 Sub-App 菜单，内容区渲染当前页面。登录、选择租户、无租户、403、404 改为不带布局的独立页面；路由守卫负责未登录跳转、登录后回到原页面和无权限拦截。见[规格第 2、3 节](../spec.md#2-目标界面)。

**Blocked by:** 01 会话改存 localStorage

**Status:** ready-for-agent

- [ ] 用 `el-container`、`el-header`、`el-aside`、`el-menu` 重写 `App.vue`；菜单数据沿用 `access.menus()` 与 `menuSections`，内置管理页按 `iam:member:manage`、`iam:org:manage`、`iam:role:manage` 显示；当前路由对应的菜单项高亮
- [ ] 顶栏：产品名、现有 `TenantSwitcher`、用户菜单（显示登录名，含「退出」）
- [ ] `LoginPanel` 拆为 `/login`（沿用账号密码、验证码、注册三种模式）、`/select-tenant`、`/no-tenant` 三个整页；`/select-tenant` 在页面刷新、内存中的授权参数丢失后回到 `/login`
- [ ] `/` 为首页，显示欢迎语与当前租户；新增 `/403`；兜底路由改为显示 404 页；`/invitations/accept` 路径与行为不变
- [ ] 内置管理页的路由元信息声明所需权限码
- [ ] `router.beforeEach`：未登录访问需登录页面跳 `/login?redirect=<原路径>`，进入租户后回到 `redirect`；`redirect` 只接受以 `/` 开头且不以 `//` 开头的路径，否则回首页；已登录访问 `/login` 回首页；缺少路由声明的权限码时跳 `/403`
- [ ] `refreshAccess` 中「菜单不再包含当前 Sub-App 时回首页」的行为保留
- [ ] 本工单不移除 spike 诊断元素，暂放在首页，由 03 处理，保证 `federation.spec.ts` 继续通过
- [ ] 测试：Vitest 覆盖守卫（未登录跳转、`redirect` 回跳、站外 `redirect` 回首页、403）与菜单渲染；更新受影响的 `App.spec.ts`、`LoginPanel.spec.ts`；Playwright 的 `tenant-login.spec.ts` 与 `federation.spec.ts` 按新页面结构更新后全部通过
- [ ] `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 通过
