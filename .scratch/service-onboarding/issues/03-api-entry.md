# 03: 业务请求统一走 /api，Shell 固定两条代理

**What to build:** SDK 的 `api()` / `page()` 在路径前加 `/api`；Shell（开发 Vite、生产 Nginx）与子应用独立宿主只保留两条固定代理 `/api`（剥掉前缀）与 `/iam`（认证端点，不剥）。网关注册与路由契约不变。删除 `FORGE_API_PREFIXES` 与 `sub-app-kit` 的 `apiPrefixes`。见[规格](../spec.md#acceptance)与 [ADR-0024](../../../docs/adr/0024-api-entry-stripped-by-shell.md)。

**Blocked by:** 无

**Status:** resolved

- [x] `frontend/packages/sdk/src/auth.ts`：`api()` / `page()` 统一在路径前加 `/api`；`request()` 的同源校验、`authPath`、直接 `fetch` 的认证端点与 OAuth 回调一律不变
- [x] `frontend/apps/tenant-app/vite.config.ts`：`server.proxy` 改为固定两条——`/api`（`rewrite` 去掉 `/api`）与 `/iam`——不保留逐前缀条目，也不为未知路径加通配代理
- [x] `deploy/images/shell-entrypoint.sh`：删除 `FORGE_API_PREFIXES` 与其校验，生成固定 `/api`（`rewrite` 去掉前缀）与 `/iam` 两个 location；SPA 回退、`/assets/`、`/index.html`、`/runtime-config.json` 行为不变
- [x] `frontend/packages/sub-app-kit/src/vite.ts`：宿主代理固定 `/api`（strip）与 `/iam`，删除 `apiPrefixes` 选项；`bin/create.ts` 生成的模板同步删除该选项与相关注释
- [x] `frontend/apps/demo-sub-app`、Lowcode 子应用：源码路径不变，通过 `/api` 正常访问；不改业务路径
- [x] 验证同源约束仍生效：跨源绝对路径仍以 `auth.invalidPath` 失败
- [x] 验证认证链路不受影响：登录、Refresh、OAuth2 回调、Cookie 语义与改动前一致；HMR、静态资源、未知路径不被 `/api` 抢走
- [x] 浏览器验证（真实 Shell + 子应用）：页面业务请求到达业务服务并返回 API JSON；刷新页面不落到 Vite HTML（最终隔离 Java 全栈已验证 JSON 请求与整页刷新，见集成验收）
- [x] 文档与脚手架同一变更内更新：`docs/sub-app.md`、`docs/service-onboarding-checklist.md` §4、`deploy/images/README.md`、`CHANGELOG.md`（破坏性）、`forge-service-archetype` 与 `sub-app-kit` 模板
- [x] 反向检查：新增一个服务不再需要修改任何前端代理配置（在规格基线里记录为 0 处）

## Comments

实现与代理边界验证已完成，见 [验收记录](../acceptance-03-api-entry.md)。真实浏览器覆盖 Vite/Nginx Shell 加载 Demo 和 Lowcode；上游使用 HTTP 替身，Java/IAM/业务服务全栈由集成验收负责人另补证据。外部 Lowcode 仅保留配置的一处未提交修改，未发布依赖或部署。

最终集成补充：真实 Java/IAM/网关的原有主链路 3 项及接入流程 3 项均通过；Demo 请求为 `/api/demo/orders`，返回 JSON，整页刷新后订单仍正常显示。

## Answer

公开 SDK 业务入口、固定代理、模板及文档已同步；同源、认证路径、HMR、静态资源、SPA 与浏览器 JSON 响应的边界验证通过。详细命令、截图与外部仓库补丁见上述验收记录。
