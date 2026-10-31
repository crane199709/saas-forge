# Sub-App 接入与 App Registry

工单：[21](../.scratch/saas-forge-mvp/issues/21-app-registry-dynamic-sub-apps.md)。接入契约见[规格第 12 节](../.scratch/saas-forge-mvp/spec.md#12-前端)与 [ADR-0009](adr/0009-module-federation-micro-frontends.md)，技术验证见[工单 02 结论](../.scratch/saas-forge-mvp/issues/02-module-federation-spike-results.md)。

## 加载链路

1. Operator 在 platform-console 的「应用注册表」登记 Sub-App：编码、各环境的 `remoteEntry.js` 地址、版本、所需最低共享依赖版本（`POST/PUT/DELETE /platform/sub-apps`）。每次变更经 Outbox 广播 `SubAppChanged`（Topic `forge-app`），iam 保存副本。
2. 业务服务在 `manifest.json` 的菜单叶子节点以 `"app": "<编码>"`、`"path"` 指向 Sub-App 路由；Plan 的 Feature Pack 中列出租户可用的 Sub-App 编码。
3. tenant-app 读取 `GET /iam/auth/access`，响应的 `subApps` 是可加载的 Sub-App：菜单按权限可见、套餐允许（未登记 Plan 的租户不限），且在 App Registry 中登记了当前环境入口的那些。当前环境由 iam 的 `forge.iam.sub-app.environment`（环境变量 `FORGE_ENVIRONMENT`，本地为 `local`）决定。指向套餐外 Sub-App 的菜单节点由 iam 隐藏。
4. Shell 为每个 Sub-App 先注册占位路由 `/apps/<编码>`，在后台各自加载，超时 15 秒。超时、加载失败或共享依赖不满足时只在该 Sub-App 的区域提示错误，Shell 首页、导航与其他 Sub-App 照常可用。Shell 提供的共享依赖版本低于登记的最低版本时不请求入口，直接拒绝。
5. 修改注册表中的地址即切换版本：用户刷新页面后加载新地址，Shell 无需重新发布。已加载的页面不会被强制替换（工单 02 结论）。
6. 切换租户时 Shell 先卸载全部 Sub-App（回到首页并移除其路由），清空共享 Pinia 状态，再整页重载。

Sub-App 经共享的 `@crane199709/sdk` 取得 Token 与租户上下文：`useTenantClient()` 返回 Shell 的客户端，`session()` 带当前租户，`api()` 自动携带 Token 并在过期时刷新。Sub-App 自身不处理登录。

## 新建 Sub-App

### 仓库外开发

先配置团队提供的 npm registry 和认证（包括 `@crane199709` scope），使用与目标 Shell 同一发布版本。Node.js 24 与 pnpm 12 可用于以下命令：

```bash
pnpm --package=@crane199709/sub-app-kit@0.1.0 dlx forge-create-sub-app billing 5180
cd billing-sub-app
pnpm install
pnpm lint
pnpm typecheck
pnpm build
pnpm dev
```

可追加目标目录：`forge-create-sub-app billing 5180 ./business/billing`。默认是当前目录的 `billing-sub-app`，目标已存在即失败，不覆盖文件。发布包的命令是编译后的 JavaScript。

生成器使用随 sub-app-kit 发布的版本表（构建时取自同版本 tenant-app），共享依赖使用精确版本，三个 `@crane199709/*` 包使用统一发布版本；无需平台源码或父目录 tsconfig。不要单独升级共享依赖，混合版本兼容不在当前支持范围内。

首页带 `ListPage`、分页、加载状态与错误提示，默认请求 `/billing/orders`，请按业务接口修改路径与行字段。开发宿主通过 `vite.config.ts` 的 `apiPrefixes` 代理业务前缀，`gateway`、`shellOrigins` 按环境设置。未接入后端时列表展示加载错误，不伪造业务数据。

### monorepo 内开发

在 `frontend` 目录执行：

```bash
pnpm create:sub-app billing 5180
```

生成 `apps/billing-sub-app`，随后执行 `pnpm install`，把精确版本依赖链接到仓库内同版本包。生成的工程使用 `@crane199709/sub-app-kit`：

| 命令 | 作用 |
| --- | --- |
| `pnpm --filter @forge/billing-sub-app dev` | 独立开发宿主，`http://localhost:5180`，支持 HMR，不需要启动 tenant-app。宿主按 Shell 的方式创建应用实例，提供登录栏，经开发服务器把 `/iam` 与 `apiPrefixes` 代理到本地网关 |
| `pnpm --filter @forge/billing-sub-app lint` | 隔离约定检查（`forge-sub-app-lint`），`build` 前自动执行 |
| `pnpm --filter @forge/billing-sub-app build` | 产出 `dist/remoteEntry.js`，部署后把地址登记到 App Registry |

开发模式不启用 Module Federation，Sub-App 直接使用自己的依赖副本，因此 HMR 照常生效；联邦加载与共享单例只在 build/preview 下由真实 Shell 验证（`pnpm test:e2e`）。

## 发布到租户 Shell

1. 执行 `pnpm build`，部署整个 `dist` 到静态服务，确认 `remoteEntry.js` 与相对资源可访问，并允许目标 Shell 的跨源请求。
2. Operator 在「应用注册表」登记编码 `billing`、当前环境入口 URL、应用版本及最低共享依赖版本；共享依赖版本应取本次生成的 package.json。环境名须与 iam 的 `FORGE_ENVIRONMENT` 一致。
3. 业务服务 Resource Manifest 的菜单叶子声明 `"app": "billing"`、`"path": "home"`，绑定业务权限码；修改后递增 Manifest version 并重新上报。
4. 在租户使用的 Plan 的 Feature Pack 中开放 `billing` 及对应 Permission Code，并为成员分配包含该权限的 Role。仅登记入口不会自动给成员授权。
5. 刷新租户 Shell，在拥有权限的租户中确认菜单可见且页面加载成功。若不可见，依次核对菜单权限、套餐范围、登记环境与 `GET /iam/auth/access` 返回的 subApps。

## 使用 `@crane199709/ui`

`@crane199709/ui` 与 Shell 共享同一实例，组件内置文案经 local scope 取 Shell 当前语言（第一期为 `zh-CN`），Sub-App 无需注册。

### 列表页 `ListPage`

`ListPage` 提供搜索区插槽、`el-table` 与底部的总条数和翻页，直接接收 `@crane199709/sdk` 的 `client.page<T>()` 结果（`Page<T>`，页码取自 `meta.page.number`，从 1 开始）。翻页时触发 `page-change`，参数是新页码，由 Sub-App 按新页码重新请求；只有一页时不显示翻页。示例见 `apps/demo-sub-app/src/OrdersList.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElTableColumn } from 'element-plus'
import { useTenantClient, type Page } from '@crane199709/sdk'
import { ListPage } from '@crane199709/ui'

interface Order {
  id: string
  title: string
}

const client = useTenantClient()
const orders = ref<Page<Order> | null>(null)
const busy = ref(false)

async function load(page = 1) {
  busy.value = true
  try {
    orders.value = await client.page<Order>(`/billing/orders?page=${page}&size=20`)
  } finally {
    busy.value = false
  }
}
onMounted(() => load())
</script>

<template>
  <ListPage :page="orders" :loading="busy" empty-text="暂无订单" @page-change="load">
    <template #search><el-button @click="load()">刷新</el-button></template>
    <el-table-column prop="title" label="标题" />
  </ListPage>
</template>
```

| 属性 / 事件 / 插槽 | 说明 |
| --- | --- |
| `page` | `Page<T> \| null`，首次加载完成前为 `null`，此时不显示分页信息 |
| `loading` | 表格显示加载遮罩，翻页不可操作 |
| `empty-text` | 没有数据时表格中的提示 |
| `page-change` | 翻页，参数为新页码 |
| `#search` | 搜索与操作区，位于表格上方 |
| 默认插槽 | `el-table-column` |

加载失败时用 `ErrorAlert` 展示提示与 traceId：`<ErrorAlert v-if="error" :tip="{ message, traceId }" />`，错误码到文案的转换由 Sub-App 自己完成。

### 主题 token

主色由 Shell 调用 `applyTheme(color)` 写入文档根节点：`--forge-color-primary`、`--el-color-primary` 及 Element Plus 的派生色 `--el-color-primary-light-3/5/7/8/9`、`--el-color-primary-dark-2`。Sub-App 不调用 `applyTheme`，也不修改文档根节点；样式中读取这些变量即可随 Shell 切换主题，Element Plus 组件自动跟随：

```css
.summary {
  border-left: 4px solid var(--forge-color-primary);
}
```

`themeTokens` 是预置的主色取值，供 Shell 选择主题，Sub-App 不应硬编码其中的颜色。

## 隔离约定检查

`forge-sub-app-lint` 检查 `src` 下的 `.ts` 与 `.vue`（`src/dev.ts` 除外），违反时列出位置并失败：

- 不得调用 `createApp`、`createRouter`、`create*History`、`createPinia`、`createI18n`：这些实例由 Shell 创建。
- 只能从共享依赖（vue、vue-router、pinia、element-plus、vue-i18n、`@crane199709/ui`、`@crane199709/sdk`）的根入口导入，不得导入子路径（包括 Element Plus 样式）。
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
