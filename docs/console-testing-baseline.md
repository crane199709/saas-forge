# 共享前端测试基线

> 2026-09-22：旧双 Console、前端构建及浏览器验收入口已迁出。本页相关命令仅供历史追溯，不再是当前后端操作入口；服务端迁移、引导及专项服务验收仍保留。当前边界和待迁检查见 [迁出记录](acceptance/consoles-extraction.md)。

当前技术栈为 Vue 3 / Soybean Admin Element Plus。历史验收仍保留原事实；迁移后的检查不以旧截图作为新界面基线。

| 边界 | 当前入口 |
| --- | --- |
| 类型与格式 | `pnpm --dir consoles run typecheck`、`lint`、`format:check` |
| 纯 Runtime / API / 品牌 | `pnpm --dir consoles run test` |
| 凭据、品牌、依赖、语言资源与本地开发边界 | `pnpm --dir consoles run test:boundaries` |
| 正式产品路由、创建未知、原操作恢复、退出保护与权限 | `consoles/integration-test/console-vue-products.test.mjs` |
| 跨标签会话、失效隐藏、恢复与语言 Origin 隔离 | `console-default-realm.test.mjs`、`session-tabs.test.mjs` |
| 浅色/深色布局、双语认证、键盘、axe 与截图 | `pnpm --dir consoles run test:browser:consumers` |
| 固定 Linux 截图比较 | `bash scripts/verify-console-visual.sh` |
| 产物及共享版本 | `pnpm --dir consoles run build:workspace` |

视觉候选通过 `bash scripts/verify-console-visual.sh --update` 在隔离副本生成；审阅后才更新基线。保留缺失基线及差异失败传播。布局夹具、模拟 HTTP 和真实服务验收是不同证据。

真实产品与四域安全验收仍执行 `bash scripts/verify-console-authentication-e2e.sh --product` 及相应 Issue 要求，使用 Chrome、受信 HTTPS、Gateway 和本次运行数据。日常改动不自动启动完整 Compose 或接管开发者服务。
