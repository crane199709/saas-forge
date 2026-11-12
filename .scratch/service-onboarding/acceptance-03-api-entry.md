# 工单 03：API Entry 实现与验收

日期：2026-11-12。范围：SDK、两个 Shell、独立宿主、模板和必要文档。按已授权的 SDK/HTTP 代理 seams 使用 TDD；SDK 与 Vite 先失败再通过，Nginx 旧脚本在未配置旧变量时失败，新脚本通过。

## 实现

- 两个 Realm 的公开 `api()` / `page()` 强制添加 `/api`，原始路径先做同源检查；`request()` 的同源校验保持原样。
- 账号安全方法原先复用了内部 `api()`，现保留独立的内部信封调用，继续使用 `/iam/auth`；登录、OAuth、Refresh、Cookie 路径不变。
- tenant-app、platform-console、sub-app-kit 固定两条代理，`/api` 剥离、`/iam` 透传，正则限定完整路径段；Nginx 固定同样的两个 location。
- 删除 `FORGE_API_PREFIXES`、`apiPrefixes`，Demo 与生成模板的业务源码路径保持不变。现有单元与浏览器 HTTP 替身分发适配新入口。
- 两份规范随 archetype 与 sub-app-kit 构建复制；已用 `cmp` 核对构建副本与规范原文一致。指南、接入清单、镜像说明及破坏性 CHANGELOG 同步。

## 自动验证

| 验证 | 结果 |
| --- | --- |
| SDK 公开客户端（tenant-app/auth-client.spec.ts） | 54 条通过，含两个 Realm 的 api/page 前缀、查询参数与跨源拒绝；既有登录/MFA/OAuth/Refresh 回归通过 |
| 全前端单元 | UI 67、tenant-app 229、kit 7、platform-console 91 条均覆盖通过 |
| 单元复验说明 | 全量首次暴露旧 HTTP fixture 路径，修复后重跑受影响模块；平台两条在并发负载中超过 5 秒，限制 maxWorkers=2 复跑其两个文件，20 条通过；未调高测试超时 |
| 现有 Playwright | 首轮 60/65 通过；5 条导入因端口锚定旧路径 mock 失败，修复后仅重跑该文件，5/5 通过 |
| 真实 Vite HTTP | 新服务无需 apiPrefixes，剥离路径与查询参数正确，/iam Cookie 透传，/apiXYZ、/iamXYZ、未知路径保留 SPA，@vite/client 仍可访问 |
| 真实 Nginx HTTP | 独立容器中 /api 剥离、/iam Cookie 透传、未知路径/SPA、assets 长缓存与 404、index/runtime no-store 均通过 |
| 类型/构建/格式 | pnpm typecheck、两个 Shell 与 Demo v1/v2、SDK/kit 构建、archetype process-resources、Prettier、git diff --check 通过 |

主要命令（前端命令在 `frontend/`）：

```sh
pnpm test
pnpm --filter @forge/platform-console exec vitest run
pnpm --filter @forge/platform-console exec vitest run src/ops/DeadEventPanel.spec.ts src/tenant/TenantPanel.spec.ts --maxWorkers=2
pnpm --filter @forge/tenant-app exec vitest run
pnpm exec playwright test --output=/private/tmp/onboarding-03-e2e-results
pnpm exec playwright test e2e/import-confirmation.spec.ts --output=/private/tmp/onboarding-03-e2e-retry
pnpm typecheck
pnpm --filter @forge/platform-console build
pnpm build:spike
pnpm --filter @crane199709/sdk build
pnpm --filter @crane199709/sub-app-kit build
# 仓库根目录
node --test deploy/images/shell-entrypoint.test.mjs
# backend/
./mvnw -o -pl forge-service-archetype process-resources -DskipTests
```

## 真实浏览器：Shell + Demo/Lowcode

Browser plugin not available，使用已安装 Playwright Chromium。没有 `page.route`；使用实际 HTTP 上游替身代替 Java 网关/IAM/业务服务，验证浏览器→真实 Shell 代理→HTTP 上游边界。本记录不自证真实 Java 服务认证、权限或 Lowcode 数据库操作；这些由集成验收负责人追加全栈证据。

- 开发 Shell：`http://127.0.0.1:15974`，Demo remote 15975、Lowcode remote 15976；生产 Shell：独立 Nginx 随机本地端口。
- 视口：Shell 1440×900；独立宿主 1280×720。现有 Playwright 另覆盖 390 宽联邦弹层。
- 路径：页面登录 → 真实 HTTP 登录 Cookie → OAuth 授权重定向回 Shell → 换码 → Demo 列表 → 401 后 Refresh → 原业务请求重放 → 刷新列表 → 整页刷新 → Lowcode 数据源 → 整页刷新。
- Demo 与 Lowcode 均显示上游返回的行，业务响应 Content-Type 为 JSON；业务请求未携带 IAM Cookie，认证 Cookie 的 `Path=/iam`、HttpOnly、SameSite=Lax 保持。
- URL、页面标题、非空、无 Vite overlay、无未解释控制台错误均通过；故意制造的首次 401 为预期。
- 独立开发宿主修改 DemoApp.vue 标题触发 `hmr update`，窗口 sentinel 保留，确认没有整页刷新。临时 harness 初次把 preview 的 production NODE_ENV 带给开发宿主，修正 harness 的 development 模式后通过；生产代码未因此增加改动。
- 5 张截图已逐张查看，列表、导航与宿主内容正常，无本次改动导致的结构变化。

本机临时证据：

- `/private/tmp/onboarding-03-browser/check.mjs`：可执行浏览器 harness。
- 同目录 `network-events.json`：50 个上游 HTTP 请求的路径、Cookie、Authorization（均为测试假凭证）。
- 同目录 `vite-demo.png`、`vite-lowcode.png`、`nginx-demo.png`、`nginx-lowcode.png`、`standalone-hmr.png`。
- 单元日志 `/private/tmp/onboarding-03-{tenant,platform}-unit*.log`。
- Nginx 测试与浏览器容器已清理；未操作现有开发数据库或容器数据。

## 外部 Lowcode 变更

源码：`/Users/liuhuan/workspace/coding/sub/lowcode`，修改前 main 工作区干净。SDK/UI/kit 均为 `0.2.0-SNAPSHOT`。只将 `lowcode-sub-app/vite.config.ts` 的 `apiPrefixes: ['/lowcode']` 删除，保留未提交的外仓本地变更，未发布或替换其正式依赖。完整补丁见 [lowcode-api-entry.patch](lowcode-api-entry.patch)。

复制 Lowcode 到 `/private/tmp/onboarding-03-browser/lowcode`，链接本票 SDK/UI/kit，直接用本票 Vite 构建（原业务源码未改）；实际 Shell 联邦加载此产物，浏览器 `/api/lowcode/data-sources` 到上游成为 `/lowcode/data-sources`，行数据与刷新通过。已安装旧包不等于新协议已发布，不能据此声称外仓正式部署完成。

## 接入成本反向检查

用未登记到任何前端配置的 `new-service` 通过真实 Vite 与 Nginx 请求，均到达上游 `/new-service/orders`。新增服务前端代理改动为 **0 处**；Nacos 网关 routes/services 的原契约不变。旧逐前缀代理不保留。
