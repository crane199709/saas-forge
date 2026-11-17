# 工单 03：已有 Sub-App 升级验收

日期：2026-11-17。工单：[03](issues/03-sub-app-upgrade.md)。基于集成 `326ff1b6`；本票全部验收通过；不代表整体升级或发布就绪通过。

## 制品与样例身份

- 真实旧版：npm 官方 registry 已发布的 `@crane199709/{sdk,ui,sub-app-kit}@0.1.0`。原始 metadata/tarball 在 `/private/tmp/v020-upgrade/published-0.1.0/`，本入口再次按原 metadata 的 integrity 核对。
- 候选：`54115992e00691be57b912e46593709c77c344a1`，`0.2.0-SNAPSHOT`；沿用工单 01 实际打包制品，未修改制品源码或重新打包。
- 候选清单 SHA-256：`999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`；三包候选摘要见 [01 验收](acceptance-01.md)。每次 `candidate.sh use` 先核对候选全部制品摘要，再重建新的本机临时 registry。
- 旧工程由校验后的真实旧 kit CLI 在仓库外生成。随后建立固定代表性业务变更：订单页、自有采购复核页和 `purchasing` 相对路由、`1.7.3` 业务版本、自定义 `business:check` 命令、Vite sourcemap/define、环境网关与 Shell CORS。旧版业务列使用最小宽度、溢出提示与金额右对齐，升级时保留。
- 实际升级依赖从同版本参考工程合并；不复制其 `src`、路由、index 或 Vite 配置。保留业务源码摘要，独立格式化基线后与升级工程逐文件比较；只删除旧 `apiPrefixes`，保留业务请求 `/smoke/orders`。

| 已发布旧包 | SHA-256 |
| --- | --- |
| sdk-0.1.0.tgz | `18b8bff606c4e6f5bfc53400f9d8e260f694d79b7ea966c647489e8849aaf12e` |
| ui-0.1.0.tgz | `5ffc32fb549c52ff557860591f8fdbcfd36ad496800412631079d51d4a4b7619` |
| sub-app-kit-0.1.0.tgz | `8a4e18ccabbc8aa894bbc851e38007a02f0389d60b37b5d9cf14e73e9c3e0b46` |

## 复现入口

```bash
FORGE_E2E_OFFLINE=1 python3 /private/tmp/v020-upgrade/run-heavy.py \
  bash scripts/release/candidate.sh use /private/tmp/v020-upgrade/candidate-54115992 \
  bash deploy/upgrade-smoke/sub-app.sh /private/tmp/v020-upgrade/03-final \
  /private/tmp/v020-upgrade/published-0.1.0
```

输出目录必须尚不存在。重跑时更换目录；省略旧包目录则从 npm 官方源匿名下载并检查 integrity。后端使用候选中新建模板服务 JAR，允许用 `FORGE_TEMPLATE_SERVICE` 显式提供；本票不以此冒充后端升级验收。公开入口见 [前端升级清单](../../docs/upgrading-sub-app-v020.md)。

## 已验证结果

- `03-build-2.log` / `03-build-3.log`：真实 0.1.0 基线安装和构建通过；缺少 `format:check` 的真实红灯记录在样例 `red-missing-format.log`；升级后安装、格式、隔离 lint、stylelint、类型、业务自定义命令、build 均通过。
- `03-run-3/upgrade-audit.json`：安装后的三包均为候选精确版本；记录业务版本、全部依赖、两份锁文件摘要、业务源码摘要及 `remoteEntry.js` 摘要。源码保留校验通过。
- `03-visual-final-update.log`、`03-visual-final-compare.log`：样例 `pnpm test:visual:update` 与 `pnpm test:visual` 各 4/4，通过 1440×900、1024×768 的长标题列表及空列表。四张最终截图逐张实际查看：无长标题折行、金额列右对齐、操作可达、无遮挡裁切。截图在 `03-run-3/app/e2e/visual/__screenshots__/`；该轻量视觉样例使用假会话和 HTTP mock，不证明真实认证。
- `03-e2e-1.log`：首次真实 Shell 已到达自有采购复核页并完成真实业务读取，随后测试定位被路由切换前后两个加载遮罩的严格模式拦住。修复为等待所有遮罩退出，并把主题读取限定到业务刷新按钮；原失败截图/trace 在 `03-run-2/evidence/red-results/`。
- `03-final.log`：最终公开入口重新生成/升级、全部前端检查及源码审计通过；真实 Shell 的第一次主题断言错误地假定点击后的按钮仍是正文色，已修正为显式 hover 并验证宿主主色，红灯保存在 `03-final/evidence/red-results/`。
- `03-host-final.log`：主题、采购复核页、请求与截图均通过，随后旧业务升序与新建模板降序预期的差异被租户 B 断言暴露；按实际保留的升序修正测试，业务源码未改，红灯在 `03-final/evidence/red-sort-results/`。
- `03-host-final-2.log`：最终两个真实浏览器用例均通过（Shell 1/1、独立宿主 1/1）。Shell 真实 IAM 登录 A，经 Manifest 菜单加载升级 `remoteEntry.js`，读取 A 本人订单；进入/返回自有采购复核页并刷新 `/api/smoke/orders` 200。匿名401、创建201、内部接口403、切租户B只见B订单且创建403均通过。保留旧业务的升序请求，未改写原页面。
- 独立宿主顺序复用 IAM 回调源，真实登录 A，经原刷新按钮读取 A 本人订单与本轮新建订单；同样完成采购复核相对路由、业务请求、主题主色及按钮 hover 实际样式验证。两种宿主均无页面异常、无已登录阶段控制台错误、无 Vite overlay。
- 两种宿主的 1440×900、1024×768 四张终态采购复核截图及 Shell 首页截图均逐张实际查看：表格/金额对齐、刷新与返回操作可达、没有加载遮罩或裁切。终态日志和截图在 `03-final/evidence/`，平台日志来自本轮 `run-papKlaev`，已归档到 `platform-final/`，无需依赖实施工作树。
- 最终升级 `remoteEntry.js` SHA-256 为 `052b4671e297b238beb581bea925398ef4005b365fc6a9199051184c758f71aa`；浏览器验收前后摘要一致，两个运行方式来自同一升级工程。制品来源清单在 `03-final/provenance.json`，实际安装/锁文件/业务源码审计在 `03-final/upgrade-audit.json`。
- 仓库新增浏览器用例的 TypeScript 与 Prettier 检查、脚本 Python/Shell 语法、`git diff --check` 通过。

构建/升级入口通过后只修正浏览器预期，最终按下面命令复验受影响的两个真实宿主，没有重复生成或替换业务产物：

```bash
FORGE_SUB_APP_UPGRADE=1 \
FORGE_TEMPLATE_SUB_APP=/private/tmp/v020-upgrade/03-final/app \
FORGE_TEMPLATE_EVIDENCE_DIR=/private/tmp/v020-upgrade/03-final/evidence \
FORGE_TEMPLATE_SERVICE=/private/tmp/v020-upgrade/candidate-54115992/templates/smoke-service/target/smoke-service-1.0.0-SNAPSHOT.jar \
FORGE_E2E_OFFLINE=1 python3 /private/tmp/v020-upgrade/run-heavy.py bash deploy/e2e/run.sh
```

## 边界

- 本轮 Browser 插件未提供，沿用仓库 Playwright/Chromium。真实验收分别运行配套 Shell preview 与同源独立宿主，保持 IAM 回调源约束；不扩大允许源或修改安全契约。
- 真实平台数据库、Redis、服务进程和本机 registry 仅属于本轮隔离环境；未操作共享开发容器/数据，未上传目标 Maven/npm 仓库或切换环境。
- 本票的业务页面沿用旧版结构，未用新模板页面覆盖它。页面/路由及格式差异可以从 `baseline/`、`reference/`、`app/` 核对；说明不提供任意工程自动升级承诺。
- 本票不替代后端数据、IO/storage、试点、完整质量门、Operator 全流程或发布后目标仓库只读复验。MQ/Nacos 在本接缝关闭。

- 实测 Node 24.14.1 / npm 11.11.0 / pnpm 12.4.2、JDK17、macOS、Playwright/Chromium。stylelint18及其write-file-atomic依赖声明Node24.15+，安装时有engine警告；本轮相关检查实际通过，不据此扩大其运行时支持范围。
