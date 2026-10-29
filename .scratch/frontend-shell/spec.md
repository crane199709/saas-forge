# 前端 Shell 整改规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-10-29 的讨论确认；会话存储改为 `localStorage` 由用户拍板，落地时新增 ADR 记录。

**开工前提**：[saas-forge-mvp](../saas-forge-mvp/spec.md) 工单 22–26 全部完成。25 的 E2E 依赖现有 `data-testid`，整改与之并行会互相干扰。

## 1. 背景与目标

第一期的前端逻辑已经完整，类型检查、单测和构建都能通过：登录、选择与切换租户、Token 刷新后权限随之更新、`v-perm`、菜单下发与 Sub-App 动态注册、切换租户时卸载 Sub-App。但界面仍是工单 02 技术验证时的骨架：

- tenant-app 把登录表单、导航链接与 `<router-view>` 堆在同一张 `el-card` 里，没有侧边菜单和顶栏。
- platform-console 没有路由，五个管理面板纵向堆在同一页。
- tenant-app 正式页面上仍显示 spike 诊断：当前路由路径、主题切换按钮、scope 探针、共享依赖单例检查（`spike.*` 文案）。
- `@forge/ui` 只有两个颜色常量；主题派生色的计算写死在 tenant-app 的 `App.vue`。Sub-App 团队没有可复用的布局与页面组件。
- 登录、选择租户、无租户都不是独立页面；没有 403 页。
- platform-console 构建为一个约 1.1MB 的单文件（gzip 约 350KB），触发 Vite 体积警告。
- 会话存于 `sessionStorage`，每个标签页需单独登录，关闭标签页即退出。

目标：

1. 两个 Shell App 具备常规管理后台布局：顶栏、侧边菜单、内容区，以及独立的登录与异常页面。
2. 正式构建中不出现技术验证用的诊断界面，诊断能力保留给开发与 E2E。
3. `@forge/ui` 提供 Shell 与 Sub-App 共用的布局、主题与常用页面组件，降低 Sub-App 团队的接入成本。
4. 会话改存 `localStorage`，同一浏览器的多个标签页共享登录，且多标签页并发刷新不会触发刷新凭证重放撤销。

## 2. 目标界面

```
┌────────────────────────────────────────────────────┐
│ SaaS Forge                 [租户：A 公司 ▾] [张三 ▾] │  顶栏
├────────────┬───────────────────────────────────────┤
│ 成员管理    │                                       │
│ 组织架构    │                                       │
│ 角色权限    │            内容区 <router-view>         │
│ ── 订单 ── │                                       │
│   订单列表  │                                       │
└────────────┴───────────────────────────────────────┘
```

- **顶栏**：左侧产品名；右侧 tenant-app 为租户切换（现有 `TenantSwitcher`）与用户菜单，platform-console 只有用户菜单。用户菜单显示当前登录名，含「退出」。
- **侧边菜单**：`el-menu`，当前路由对应项高亮。tenant-app 依次列出 Shell 内置管理页与后端下发的 Sub-App 菜单分组；platform-console 列出平台管理页。均按权限过滤。
- 登录、选择租户、无租户、邀请落地、403、404 为不带布局的整页。
- 只针对桌面宽度设计，不做移动端适配。

## 3. tenant-app 路由

| 路径 | 页面 | 需登录 | 说明 |
|---|---|---|---|
| `/login` | 登录 | 否 | 沿用现有三种模式：账号密码、验证码、注册 |
| `/select-tenant` | 选择租户 | 否 | 登录后有多个有效 Membership 时进入 |
| `/no-tenant` | 无租户 | 否 | 登录后没有有效 Membership 时进入 |
| `/invitations/accept` | 接受邀请 | 否 | 现有页面，路径不变（iam 的 `accept-url` 指向这里） |
| `/` | 首页 | 是 | 显示欢迎语与当前租户；不做仪表盘 |
| `/members`、`/org`、`/roles` | 内置管理页 | 是 | 现有页面，路径不变；路由元信息声明所需权限码 |
| `/apps/<appCode>/...` | Sub-App | 是 | 现有动态注册机制不变 |
| `/403` | 无权限 | 是 | |
| 其他 | 404 | 否 | 现有兜底路由改为显示 404 页 |

**路由守卫**（`router.beforeEach`）：

- 未登录访问需登录的页面：跳转 `/login?redirect=<原路径>`。登录并进入租户后回到 `redirect`。`redirect` 只接受以 `/` 开头、不以 `//` 开头的站内路径，否则回首页，防止开放重定向。
- 已登录访问 `/login`：回首页。
- 路由声明了权限码而当前用户没有：跳转 `/403`。按钮级权限仍由 `v-perm` 控制，接口仍由后端校验，守卫只决定页面能否进入。
- `/select-tenant` 依赖登录后内存中的授权参数（`createRealmClient` 的 `authorization`）。页面刷新后该参数丢失，此时回到 `/login`。
- 现有 `refreshAccess` 中「菜单不再包含当前 Sub-App 时回首页」的逻辑保留。

## 4. platform-console 路由

| 路径 | 页面 | 所需权限 |
|---|---|---|
| `/login` | 登录 | 无需登录 |
| `/tenants` | 租户 | `tenantRead`（管理操作仍按 `tenantManage` 控制） |
| `/plans` | 套餐 | `planManage` |
| `/sub-apps` | 应用注册表 | `subAppManage` |
| `/operators` | 运营账号 | `operatorManage` |
| `/roles` | 平台角色 | `roleManage` |
| `/403`、404 | 异常页 | |

- 权限码沿用 [access.ts](../../frontend/apps/platform-console/src/access.ts) 中的 `permissions`。
- `/` 跳转到第一个有权限的页面；一个都没有时显示现有的「无任何权限」提示（`access.none`）。
- 面板组件本身不改，只是由 `App.vue` 内联改为各自的路由页面。现在由 `App.vue` 统一读取并下发的 `plans`、`subApps` 列表，改为由使用它的页面自行读取。
- 守卫规则同第 3 节（登录跳转、`redirect` 校验、403）。

## 5. 技术验证诊断收纳

- 当前路由显示（`shell-route`）、主题切换（`shell-theme`）、scope 探针（`shell-scope`）、共享依赖单例检查（`shared-*`）移到 tenant-app 的 `/__diagnostics` 页面。
- 该路由只在开发模式（`import.meta.env.DEV`）或构建时设置 `VITE_FORGE_DIAGNOSTICS=true` 时注册；E2E 构建打开该开关，正式构建不包含这些代码与 `spike.*` 文案。
- 主题切换本身不是产品功能，只作为诊断项保留，用来验证 Shell 与 Sub-App 共用主题 token。
- 修改 [e2e/federation.spec.ts](../../frontend/e2e/federation.spec.ts)，相关断言改为访问 `/__diagnostics`，原有验证点一个不少。

## 6. `@forge/ui`

只沉淀两个 Shell 或 Sub-App 实际重复使用的部分：

| 导出 | 用途 |
|---|---|
| `AppLayout` | 顶栏 + 侧边菜单 + 内容区。菜单项由 props 传入（标题、路径、子项），顶栏右侧为插槽。两个 Shell 共用 |
| `applyTheme(color)` | 把主色及 Element Plus 派生色（`--el-color-primary-light-*`、`-dark-2`）写入文档根节点。逻辑从 tenant-app `App.vue` 迁出 |
| `ErrorAlert` | 展示错误提示，从 tenant-app 迁出。错误码到文案的转换（`error-tip.ts`）留在各应用 |
| `ResultPage` | 403、404、无租户、无权限等结果页 |
| `ListPage` | 列表页骨架：搜索区插槽、`el-table`、`el-pagination`，分页与 `@forge/sdk` 的 `Page<T>` / `meta.page` 对接 |

约定：

- `vue`、`element-plus`、`vue-i18n` 为 peer 依赖，与现有 Module Federation 共享配置一致，不新增共享单例。
- 组件内置文案使用 local scope 的 `zh-CN` 语言包，不创建全局 i18n，与 Sub-App 的约定相同。
- 更新 [docs/sub-app.md](../../docs/sub-app.md)，说明 Sub-App 如何使用 `ListPage` 与主题 token。
- demo 子应用的订单列表（由 mvp 工单 24、25 补齐）改用 `ListPage`，作为接入示例。

## 7. 会话存储改为 localStorage

### 7.1 存储

- tenant-app、platform-console 与 `@forge/sub-app-kit` 的开发宿主（`dev-host.ts`）创建客户端时传入 `localStorage`。存储键不变：`forge.tenant.session`、`forge.platform.session`。
- 不迁移 `sessionStorage` 中的旧会话。上线后用户重新登录一次即可。

### 7.2 跨标签页同步

同一浏览器的所有标签页共享同一份会话，SDK 监听 `window` 的 `storage` 事件，在其他标签页改写会话时：

| 其他标签页的操作 | 本标签页的行为 |
|---|---|
| 刷新 Token（Membership 不变） | 通知 `onSessionChange` 订阅者，权限与菜单按新 Token 重新读取；页面不重载 |
| 切换租户（`membershipId` 变化） | 与本标签页切换相同：卸载全部 Sub-App、清空共享 Pinia 状态、回首页 |
| 退出或会话被清除 | 回到 `/login` |
| 登录 | 停留在 `/login` 的标签页进入首页 |

- 会话被其他标签页改写时，`generation` 也要递增：本标签页在旧会话下发起、尚未完成的请求，不能带着新 Token 重发（与现有 `auth.sessionChanged` 语义一致）。
- 结果：所有标签页始终处于同一个租户，不支持不同标签页同时停留在不同租户。

### 7.3 刷新与切换跨标签页互斥

iam 的刷新凭证是一次性的（`reuseRefreshTokens(false)`）。已轮换的旧凭证再次使用会被视为重放，撤销整个授权族（`TenantLoginTest.refreshReplayRevokesTheAuthorizationFamily`）。多个标签页共享同一个刷新凭证后，如果同时刷新（例如角色变更使所有标签页的下一次请求都返回 401），晚到的标签页会用已轮换的旧凭证刷新，导致所有标签页被登出。

要求：

- 刷新（`refresh`）与切换租户（`rebind`）在现有标签页内串行队列之外，再用 Web Locks API 跨标签页互斥：`navigator.locks.request('forge.<realm>.rotation', ...)`。
- 取得锁后先重新读取存储：如果刷新凭证已不同于发起时读到的值，说明其他标签页已经轮换过，直接使用存储中的新会话，不再调用刷新接口。
- 只有刷新凭证被拒绝时才清除会话，这一点保持现状；服务端或网络故障时保留会话。
- 目标浏览器均支持 Web Locks，不做降级实现。单测环境（jsdom）没有 `navigator.locks`，测试中提供桩实现。

### 7.4 风险

- 刷新凭证有效期 7 天（`refreshTokenTimeToLive`），关闭浏览器后仍保持登录。公用电脑上必须主动退出。
- `localStorage` 与 `sessionStorage` 一样能被同源脚本读取，XSS 风险等级不变；它的保留时间更长，泄漏的影响窗口也更长。现有的重放撤销与即时失效机制（ADR-0005）不变。
- 不提供「记住我」选项。

## 8. 构建体积

- 两个 Shell 的路由页面改为懒加载（`component: () => import(...)`）。
- platform-console 不承载 Sub-App，可以按需引入 Element Plus 组件，不再全量 `app.use(ElementPlus)`。
- tenant-app **保持全量引入 Element Plus**：Sub-App 共享 Shell 的 Element Plus（`import: false`），Shell 少带组件会导致 Sub-App 运行时报错。

## 9. 验收标准

- 两个 Shell 都有顶栏、侧边菜单、内容区；菜单按权限显示，切换租户或权限变化后菜单随之更新。
- 未登录访问 `/members` 跳到 `/login?redirect=/members`，登录后回到 `/members`；`redirect` 为站外地址时回首页。
- 无权限访问 `/roles` 或 platform-console 的 `/operators` 进入 403 页；不存在的路径进入 404 页。
- 正式构建的产物中不含 `/__diagnostics` 路由与 `spike.*` 文案；E2E 构建中 `federation.spec.ts` 的全部验证点通过。
- 两个 Shell 改用 `@forge/ui` 的 `AppLayout`、`applyTheme`、`ErrorAlert`、`ResultPage`；demo 子应用的订单列表使用 `ListPage`。
- platform-console 构建不再出现 chunk 体积警告。
- 会话（Vitest）：
  - 其他标签页写入、切换、清除会话时，本标签页的行为符合 7.2 节的表格。
  - 两个客户端实例共享同一存储并同时刷新时，只调用一次刷新接口，两者都拿到新会话。
- 会话（Playwright，同一浏览器上下文开两个页面）：
  - 在 A 登录后，B 刷新即处于登录状态；关闭全部页面再打开仍处于登录状态。
  - 在 A 切换租户，B 的菜单与 Sub-App 数据随之切换。
  - 在 A 退出，B 回到登录页。
  - 管理员修改该成员角色后，A、B 同时发起请求，两者都按新权限继续工作，不被登出。
- `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 全部通过。

## 10. 不在范围

移动端适配、暗色模式、多语言、面包屑、首页仪表盘内容、「记住我」、不同标签页停留在不同租户、Sub-App 的 JS/CSS 沙箱。

## 11. 建议拆分

按顺序，每张工单独立提交：

1. 会话改存 `localStorage`，加跨标签页同步与刷新互斥（第 7 节），并新增 ADR。只改 SDK 与存储参数，不动界面，先单独上线以便观察。
2. tenant-app 布局与路由守卫（第 2、3 节）。
3. 诊断收纳（第 5 节），依赖 2。
4. platform-console 路由与布局（第 4 节）。
5. `@forge/ui` 沉淀，两个 Shell 与 demo 子应用改用（第 6 节），依赖 2、4。
6. 构建体积（第 8 节），依赖 4。
