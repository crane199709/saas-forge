# 02：Module Federation 技术验证结论

验证日期：2026-10-24。对应 [工单 02](02-module-federation-spike.md) 与 [规格第 12 节](../spec.md#12-前端)。

## 结论与范围

当前项目栈可采用 ADR-0009 的方案，无需重新决策。tenant-app 与 demo-sub-app 分别构建，浏览器通过独立端口加载 ESM remoteEntry；没有在 Shell 中静态导入 Sub-App 源码。

本次只验证内部可信 Sub-App 的技术接入。未实现 App Registry、菜单/Plan 筛选、认证、租户切换或生产部署。`@forge/sdk` 的 `sdkRuntime` 是临时模块身份标记，不提供登录或 Token 能力；`sharedReferences` 也是本 spike 的诊断接口。

## 版本与运行方式

| 组件 | 验证版本 |
| --- | --- |
| Node.js / pnpm | 24.14.1 / 12.4.2 |
| Vite / TypeScript | 8.3.5 / 5.9.3 |
| `@module-federation/vite` / runtime | 1.23.5 / 2.9.2 |
| Vue / Vue Router / Pinia | 3.5.44 / 5.4.0 / 4.1.0 |
| Element Plus / vue-i18n | 2.14.7 / 11.4.16 |
| `@forge/ui` / `@forge/sdk` | 0.1.0 / 0.1.0 |
| Playwright / Chromium | 1.64.1 / 156.0.8078.4 |

在仓库根目录执行：

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
pnpm build:spike
pnpm test:e2e
```

`build:spike` 串行构建 Shell、Sub-App v1、Sub-App v2。Playwright 使用一个 worker，自动启动并结束三个 preview 服务；占用的端口必须空闲：

- Shell：`http://127.0.0.1:5174`
- Sub-App v1：`http://127.0.0.1:5175/remoteEntry.js`
- Sub-App v2：`http://127.0.0.1:5176/remoteEntry.js`

手动体验：构建后在三个终端分别运行以下命令，打开 Shell 的 `/apps/demo/overview`：

```bash
pnpm --filter @forge/tenant-app preview --host 127.0.0.1 --port 5174
pnpm --filter @forge/demo-sub-app preview --host 127.0.0.1 --port 5175
pnpm --filter @forge/demo-sub-app preview --outDir dist-v2 --host 127.0.0.1 --port 5176
```

修改 `frontend/apps/tenant-app/dist/sub-apps.json` 的 `remoteEntry` 为 v2 地址，再刷新 Shell 即可切换；改回 5175 后刷新恢复 v1。不要重新构建 Shell，否则 public 中的默认配置会覆盖该文件。自动测试实际修改此文件，并在 finally 中恢复，不使用网络 mock。上述三个命令默认用于验收 production build；纯 Sub-App 页面不能独立挂载，因为它只消费 Shell 提供的依赖。开发模式与生产 CDN 未作为本次验收对象。

## 浏览器证据

可重复执行的用例位于 `frontend/e2e/federation.spec.ts`，未 mock Module Federation、Vue、Router 或远程服务。

| 验收项 | 证据 |
| --- | --- |
| 运行时配置加载 | Shell 读取外置 JSON，通过 ESM remoteEntry 加载不同端口的页面；页面显示 v1 |
| singleton | 对比 Shell 与 Sub-App 实际导入的 Vue `ref`、Router `useRouter`、Pinia `defineStore`、Element Plus `ElDialog`、UI token 对象、SDK 身份对象，全部严格引用相等；vue-i18n `useI18n` 同样验证 |
| 嵌套路由 | 从 Shell 首页进入概览，再进入详情 42；Shell 路径同步，浏览器后退/前进及详情直接刷新均通过；返回首页卸载 Sub-App 组件 |
| 主题 | 主色由蓝色切到紫色后，两端 Element Plus 按钮的计算样式同步变化 |
| scoped CSS | 两端使用同名 `scope-probe` class；Sub-App 为 7px 边框，Shell 保持 0px，主题切换后仍隔离 |
| Element Plus 弹层 | Dialog、Select、Tooltip 在真实浏览器中操作，检查显示、位置、计算样式及关闭；桌面 1280×900 与窄屏 390×844 |
| 无重建换版本 | 只改 dist 中 JSON，刷新后实际请求 5176 的 remoteEntry 并显示 v2；除 JSON 外所有 Shell 构建文件的 SHA-256 保持不变 |

测试同时检查页面标题、有意义内容、无 Vite 错误覆盖层、无浏览器 error/warning/pageerror。截图保存在 `/tmp/forge-02-overlays-1280.png`、`/tmp/forge-02-overlays-390.png`、`/tmp/forge-02-v2.png`；不提交截图和构建产物。

## 坑点与契约修订

1. **库 singleton 与应用实例是两件事。** Shell 统一创建 Vue app、Router、Pinia、全局 i18n，Sub-App 只导出相对路由与组件。不能让 Sub-App 自行 mount 第二个应用。
2. **共享配置不能单独证明生效。** 此 spike 既配置 singleton，又让 Sub-App 使用 `import: false`，再在浏览器比较导出引用；不以版本字符串相同作为证明。两端同版本可行，不代表已验证任意版本组合兼容。
3. **i18n 也需要共享。** 组件使用 Shell 注入的语言上下文；Sub-App 的文案放 local scope，避免覆盖 Shell 文案。
4. **弹层依赖全局主题与库 CSS。** Shell 只加载一次 Element Plus CSS。Sub-App 的 scoped CSS 不能依赖祖先容器去控制 teleport 到 body 的弹层。切换主题时，Element Plus 的 light-3/5/7/8/9、dark-2 必须与 primary 同步，否则悬停等状态仍是旧颜色。
5. **共享根包不等于共享任意子路径。** Sub-App 从共享包根入口导入，不独立引入 Element Plus 样式或绕过共享声明的内部实现路径。新增子路径应另行验证重复实例风险。
6. **加载时序与刷新。** 在首次导航前注册路由，确保深链接被匹配；Shell 静态服务器必须提供 history fallback。Sub-App 资源用相对 base，跨源资源须允许 Shell 源的 CORS；此 spike 只允许 `http://127.0.0.1:5174`。
7. **版本切换是配置加整页刷新。** 不对已加载容器调用强制覆盖。生产版本应使用不可变地址，配置/入口不能被旧缓存长期固定，hash 资源可长期缓存。此 spike 用两个本地端口验证，不代替 CDN 验收。
8. **自动化环境。** 当前会话没有 Browser 插件，使用 Playwright。资源压力较高，构建、浏览器 worker 与审查均串行；Module Federation 插件在 Vitest 中禁用，联邦运行时行为由真实浏览器测试负责。

第 12 节已明确暴露模块、路由所有权、共享提供方、主题、i18n、部署与更新契约。原 ADR-0009 的可信团队约束仍成立；scoped CSS 不是安全沙箱。脚手架/lint 强制隔离、租户切换清理、失败重试和多 Sub-App 调度留给后续工单。

## 验证命令与限制

```bash
cd frontend
pnpm --workspace-concurrency=1 -r typecheck
pnpm typecheck:tools
pnpm --workspace-concurrency=1 -r build
pnpm --filter @forge/demo-sub-app build:v2
pnpm --workspace-concurrency=1 -r test
pnpm test:e2e
pnpm format:check
```

上述检查全部通过：5 个子包类型检查、工具配置/E2E 类型检查、全部前端包构建（含 Sub-App v2）、2 个 Vitest 单测、6 个 Chromium E2E，以及 Prettier 检查。

仅变更前端和规格文档，未改后端或数据库，未启动后端依赖。浏览器覆盖 Chromium；Firefox、Safari、生产 CDN、跨版本依赖协商和开发 HMR 未验证。Element Plus 全量共享产物仍触发 Vite 的大于 500 kB chunk 提示，此工单没有做体积优化。

参考：[Module Federation Vite 插件](https://module-federation.io/integrations/build-tool/vite)、[运行时 API](https://module-federation.io/guide/runtime/runtime-api)、[插件源码与 host-only shared 说明](https://github.com/module-federation/vite)。具体可行性以上述本地构建与浏览器结果为准。
