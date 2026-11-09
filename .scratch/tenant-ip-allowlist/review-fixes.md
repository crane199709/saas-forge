# Tenant IP Allowlist 审查修复

基于 integration `083fe692`，修复 Standards 审查的 2 项硬规范违例与 1 项判断型重复代码；Spec 审查为 0 项。本文件追加修复证据，不改写原审查结论。

## 修复结果

1. IAM Operator 清除不存在或已删除 Tenant 时改用独有的 `tenant.ipAllowlistNotFound`（404），同步 IAM 枚举、消息键、HTTP 测试及工单 03 契约；用户提示仍为「租户不存在或已被删除」。Platform 既有 `tenant.notFound` 保持原归属。
2. 安全设置的条目错误在 `userTip` 缺失、空字符串或纯空白时使用已有 i18n `error.unknown`（「请求失败，请稍后重试」），不展示机器码。真实 Shell + SDK 的公开 HTTP 响应回归覆盖三个场景；既有 Playwright 桌面/移动场景补充缺失和纯空白响应，检查通用文案、错误项焦点和后续成功保存。
3. IAM `clientip.service.ClientIpResolver.currentIp()` 集中登录锁定、Token 透传、租户准入共用的提取逻辑：优先网关覆盖的 `ClientIpHeader`，缺失或空白时回退连接地址，对来源头 `strip`，不读取 `X-Forwarded-For`。Tenant 的静态入口保留一行委托，Controller 继续只访问本功能 service。无请求上下文时仍返回 `null`，Login Lockout 仍跳过 IP 计数，Token 请求仍不附缺失的来源头；未新增 SDK API 或策略层。

## TDD 与验证

测试边界沿用已批准的 HTTP、网关及 Playwright；没有新增内部方法测试。

- Operator 404：先观察 `expected tenant.ipAllowlistNotFound but was tenant.notFound` 的 RED，再修改生产代码后 GREEN（1/1）。新增断言同时确认中文用户提示。
- 安全设置：新增缺失/空字符串/纯空白 3 例，先观察机器码外露或无提示的 RED，再最小修改后 6/6 GREEN。
- IAM 定向 85/85：TenantLoginTest 23、OperatorIpAllowlistClearTest 10、TenantIpAllowlistTest 24、PlatformLoginTest 6、LoginLockoutTest 15、ArchitectureTest 1、TenantIpEnforcementTest 6。共享容器，单 fork，无失败/跳过。
- 前端安全设置 6/6，Tenant 类型检查、工具类型检查、修改文件 Prettier 与 Tenant App 构建通过。构建仅有既有 chunk 大小提示。
- Playwright 安全设置桌面（1280×900）、移动（390×844）2/2。Browser plugin not available，采用仓库 Playwright/Chromium，只模拟公开 HTTP。临时配置仅启动当前工作树 Tenant preview `http://127.0.0.1:5184`，避免启动无关应用。页面 URL/title、非空内容、无框架 overlay、console/pageerror 和交互检查均通过；主动模拟的 400 响应已在测试中区分。
- 已查看 `/private/tmp/tenant-ip-allowlist-review-fallback-desktop.png` 与 `...-mobile.png`：条目下显示通用中文提示，无机器码，无重叠或裁切；截图未提交。
- 当前仓库引用的 `docs/agents/frontend-design.md` 不存在；本次保留页面布局，以最小文案修正并完成截图走查。
- 交付前已合并检查最新 integration（仍为 `083fe692`，Already up to date）。本次仅跑直接相关定向验证，未重复父集成已完成的全模块与真实后端流程；未新增 WebKit/Firefox 覆盖。

```sh
cd backend
./mvnw -o -pl forge-iam -am install -DskipTests
./mvnw -o -pl forge-iam test -Dtest=OperatorIpAllowlistClearTest#missingAndDeletedTenantsReturnNotFoundWithoutChangingAnotherTenant -DforkCount=1
./mvnw -o -pl forge-iam spotless:apply test -Dtest=TenantIpAllowlistTest,TenantIpEnforcementTest,OperatorIpAllowlistClearTest,TenantLoginTest,PlatformLoginTest,LoginLockoutTest,ArchitectureTest -DforkCount=1
./mvnw -o -pl forge-iam spotless:check
cd ../frontend
pnpm install --offline --frozen-lockfile
pnpm --filter @forge/tenant-app test src/security/SecuritySettingsPage.spec.ts --maxWorkers=2
pnpm --filter @forge/tenant-app typecheck
pnpm typecheck:tools
pnpm exec prettier --check apps/tenant-app/src/security/SecuritySettingsPage.vue apps/tenant-app/src/security/SecuritySettingsPage.spec.ts e2e/tenant-ip-allowlist.spec.ts
pnpm --filter @forge/tenant-app build
pnpm exec playwright test --config=/private/tmp/tenant-ip-review-playwright.config.cjs --grep '安全设置' --reporter=line --output=/private/tmp/tenant-ip-review-playwright
```

详细本机日志：`/private/tmp/tenant-ip-review-{404-red,404-green,ui-red,ui-green,iam,frontend,build,playwright}.log`。临时配置、日志和截图均在仓库外。
