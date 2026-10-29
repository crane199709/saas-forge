# Sub-App 接入与 App Registry

工单：[21](../.scratch/saas-forge-mvp/issues/21-app-registry-dynamic-sub-apps.md)。接入契约见[规格第 12 节](../.scratch/saas-forge-mvp/spec.md#12-前端)与 [ADR-0009](adr/0009-module-federation-micro-frontends.md)，技术验证见[工单 02 结论](../.scratch/saas-forge-mvp/issues/02-module-federation-spike-results.md)。

## 加载链路

1. Operator 在 platform-console 的「应用注册表」登记 Sub-App：编码、各环境的 `remoteEntry.js` 地址、版本、所需最低共享依赖版本（`POST/PUT/DELETE /platform/sub-apps`）。每次变更经 Outbox 广播 `SubAppChanged`（Topic `forge-app`），iam 保存副本。
2. 业务服务在 `manifest.json` 的菜单叶子节点以 `"app": "<编码>"`、`"path"` 指向 Sub-App 路由；Plan 的 Feature Pack 中列出租户可用的 Sub-App 编码。
3. tenant-app 读取 `GET /iam/auth/access`，响应的 `subApps` 是可加载的 Sub-App：菜单按权限可见、套餐允许（未登记 Plan 的租户不限），且在 App Registry 中登记了当前环境入口的那些。当前环境由 iam 的 `forge.iam.sub-app.environment`（环境变量 `FORGE_ENVIRONMENT`，本地为 `local`）决定。指向套餐外 Sub-App 的菜单节点由 iam 隐藏。
4. Shell 为每个 Sub-App 先注册占位路由 `/apps/<编码>`，在后台各自加载，超时 15 秒。超时、加载失败或共享依赖不满足时只在该 Sub-App 的区域提示错误，Shell 首页、导航与其他 Sub-App 照常可用。Shell 提供的共享依赖版本低于登记的最低版本时不请求入口，直接拒绝。
5. 修改注册表中的地址即切换版本：用户刷新页面后加载新地址，Shell 无需重新发布。已加载的页面不会被强制替换（工单 02 结论）。
6. 切换租户时 Shell 先卸载全部 Sub-App（回到首页并移除其路由），清空共享 Pinia 状态，再整页重载。

Sub-App 经共享的 `@forge/sdk` 取得 Token 与租户上下文：`useTenantClient()` 返回 Shell 的客户端，`session()` 带当前租户，`api()` 自动携带 Token 并在过期时刷新。Sub-App 自身不处理登录。

## 新建 Sub-App

在 `frontend` 目录执行：

```bash
pnpm create:sub-app billing 5180
```

生成 `apps/billing-sub-app`，随后执行 `pnpm install`。生成的工程使用 `@forge/sub-app-kit`：

| 命令 | 作用 |
| --- | --- |
| `pnpm --filter @forge/billing-sub-app dev` | 独立开发宿主，`http://localhost:5180`，支持 HMR，不需要启动 tenant-app。宿主按 Shell 的方式创建应用实例，提供登录栏，经开发服务器把 `/iam` 与 `apiPrefixes` 代理到本地网关 |
| `pnpm --filter @forge/billing-sub-app lint` | 隔离约定检查（`forge-sub-app-lint`），`build` 前自动执行 |
| `pnpm --filter @forge/billing-sub-app build` | 产出 `dist/remoteEntry.js`，部署后把地址登记到 App Registry |

开发模式不启用 Module Federation，Sub-App 直接使用自己的依赖副本，因此 HMR 照常生效；联邦加载与共享单例只在 build/preview 下由真实 Shell 验证（`pnpm test:e2e`）。

## 隔离约定检查

`forge-sub-app-lint` 检查 `src` 下的 `.ts` 与 `.vue`（`src/dev.ts` 除外），违反时列出位置并失败：

- 不得调用 `createApp`、`createRouter`、`create*History`、`createPinia`、`createI18n`：这些实例由 Shell 创建。
- 只能从共享依赖（vue、vue-router、pinia、element-plus、vue-i18n、`@forge/ui`、`@forge/sdk`）的根入口导入，不得导入子路径（包括 Element Plus 样式）。
- 不得 `addRoute`/`removeRoute`、改写 `history`、整页跳转、修改文档根节点的样式，不得写 Shell 的 `forge.*` 会话存储。
- `.vue` 的 `<style>` 必须 `scoped` 或 `module`，不得使用 `:global(...)`，不得为 `html`、`body`、`:root` 设置样式。

这是对内部可信团队的约定检查，基于文本匹配，不是安全沙箱。

## 本地运行示例 Sub-App

iam 的 `demo` profile 预置了 `demo` 的副本（入口 `http://127.0.0.1:5175/remoteEntry.js`，`revision` 为 0），本地无需先登记。在 platform-console 登记或修改 `demo` 后，广播的变更会覆盖这份种子数据。

```bash
pnpm start:demo   # 构建并在 5175 提供示例 Sub-App
pnpm dev:tenant   # 另开终端，http://localhost:5174
```

新增 Topic `forge-app` 后，已有的本地 RocketMQ 需重新执行 `deploy/rocketmq/create-topics.sh`（重建 compose 时自动执行），否则 iam 订阅时找不到 Topic，启动失败（SDK 消费端的既有行为）。
