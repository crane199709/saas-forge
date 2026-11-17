# 工单 01：候选制品与新建工程验收

日期：2026-11-17。工单：[01](issues/01-candidate-new-projects.md)。状态：本票全部验收通过；不代表整项升级或发布就绪通过。

## 候选身份与消费入口

- 制品源码提交：`54115992e00691be57b912e46593709c77c344a1`；基于集成 `f9558f3a`，构建时工作区干净。
- Maven/npm 统一坐标版本：`0.2.0-SNAPSHOT`，不是产品正式版，也未上传目标发布仓库。
- 原始候选目录：`/private/tmp/v020-upgrade/candidate-54115992/`。
- 来源与全部摘要：`candidate.json`，包含 22 个 Maven 模块、312 个 Maven 文件与三个 npm tarball，共 315 个文件；`sourceChanges=[]`。
- `candidate.json` 自身 SHA-256：`999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。
- Maven 来源：该候选目录下的 `maven-repository/`（`file://`）；npm 来源：`tarballs/` 内实际 `pnpm pack` 产物，先发布本机 Verdaccio 后消费。

| 关键实际制品 | SHA-256 |
| --- | --- |
| `forge-service-archetype-0.2.0-20261117.025856-1.jar` | `94819acff83266c1780e87e94936b29e9a265924ef7463ba5719c2a14cedc3fc` |
| `crane199709-sdk-0.2.0-SNAPSHOT.tgz` | `db3b4496a9691ca718b19e7dc95a9055ff242b63b6622c35a52a2cf024cee890` |
| `crane199709-ui-0.2.0-SNAPSHOT.tgz` | `bf5f7c012df8e86e1528b2b0435f9a8a4ad933bea9c13f280d8fdbba58bc653c` |
| `crane199709-sub-app-kit-0.2.0-SNAPSHOT.tgz` | `a9c964e4aad3e18fb288051e823f7715ef894f51f5e1f4ed882a39a45863cc3d` |

临时 registry 在命令退出后清理，不能把已退出的随机 URL 当后续制品源。后续 02/03/06/07 使用当前实现分支中的入口：

```sh
bash scripts/release/candidate.sh use /private/tmp/v020-upgrade/candidate-54115992 bash
# 子 shell 内可用：
# MAVEN_RELEASE_URL=file:///private/tmp/v020-upgrade/candidate-54115992/maven-repository
# NPM_RELEASE_REGISTRY=http://127.0.0.1:<本轮随机端口>
# NPM_CONFIG_USERCONFIG=<本轮临时npmrc，配置@crane199709 scope>
# FORGE_CANDIDATE_DIR=/private/tmp/v020-upgrade/candidate-54115992
```

先核对清单与全部摘要，再把原始 tarball 放进新 Verdaccio；命令退出只删除本轮容器。Maven 消费使用 `forge-release` repository 与独立缓存；仅复用第三方缓存，排除本机全部 `io.saasforge`。npm 消费使用配套精确版本，无 workspace 链接或 tarball override。

`cdf94f24` 补齐 npm 对预发布版本要求显式 `--tag` 的临时仓库重建参数，并保留 npm 失败诊断；后续浏览器修正仅限定 Element Plus 可见选中项的定位，并等待加载遮罩与下拉菜单退出后截图。这些消费脚本/测试接缝调整未修改 `54115992` 的任何 Maven/npm 制品源码或已打包内容。候选的源码身份仍为 `54115992`，不能把后续脚本提交冒充为包来源。

## 已完成结果

| 接缝 | 执行与结果 |
| --- | --- |
| 实际制品消费 | `candidate.sh build` 复用 `publish.sh local`；隔离全部本机 Forge Maven 缓存后解析所有 SDK/架构依赖；三 npm 包安装、类型示例编译、加载发布 Vite 入口、运行编译后的 lint CLI 均通过 |
| 仓库外 Business Service | 实际 archetype 生成 `templates/smoke-service`，独立缓存执行 `verify`；17 个现有测试，零失败/错误/跳过，覆盖架构、HTTP 订单、权限、租户与表/字段注释 |
| 仓库外 Sub-App | 发布 CLI 生成 `templates/smoke-sub-app`；安装、`format:check`、`lint`、`lint:style`、`typecheck`、`build` 全部通过 |
| 打包与分发 | 规范副本头部为 `0.2.0-SNAPSHOT`，正文与唯一来源一致；Wrapper 可执行；前端三个 Forge 依赖均精确为 `0.2.0-SNAPSHOT`；默认 POM 无 IO/storage；实际 tarball 检查通过，服务模块未发布、Java 模块有源码包 |
| 配套 Shell 真实接入 | 真实 IAM 登录，Manifest 菜单点击后加载生成 Sub-App 的 `remoteEntry.js`，`/apps/smoke/home` 读到 A 本人订单；匿名网关 401、成员读 200/创建 201、内部接口 403、切租户 B 后只见 B 订单且创建 403；无页面异常或控制台错误 |
| 独立开发宿主 | Shell preview 关闭后同一回调源启动生成工程 `pnpm dev`；真实登录 A，切换金额降序后收到 `/api/smoke/orders` 200，显示 A 本人订单/模板订单；加载遮罩消失、下拉关闭、两行排序稳定后重拍，无页面异常或已登录阶段控制台错误 |
| 候选重复消费 | 修复 `--tag` 后，新随机 Verdaccio 与空 npm 缓存重新下载三包，SHA-256 全部等于原始候选；篡改包会在启动 registry 前被拒绝 |
| 直接回归 | 发布脚本已有回归 16/16、sub-app-kit 7/7；工具 TypeScript、相关 Prettier、Shell 语法、`git diff --check` 通过 |

本票修复了本地 SNAPSHOT 版本参数顺序、新建 archetype 的默认菜单路径（`overview` → `home`，菜单编码保留）以及模板验证使用仓内 Demo 替代生成前端的问题。同步 CHANGELOG、接入指南与生成提示；没有新增页面设计规则、默认 starter 必需项或可选能力。

## 本地复现与证据

新候选目录必须尚不存在；从干净提交重建会得到新的来源/摘要，不能覆盖本候选或沿用本次证据：

```sh
bash scripts/release/candidate.sh build /private/tmp/v020-upgrade/candidate-<新的提交短号>
```

复验留存的同一生成服务与前端：

```sh
FORGE_TEMPLATE_SUB_APP=/private/tmp/v020-upgrade/candidate-54115992/templates/smoke-sub-app \
FORGE_TEMPLATE_EVIDENCE_DIR=/private/tmp/v020-upgrade/candidate-54115992/evidence \
FORGE_TEMPLATE_SERVICE=/private/tmp/v020-upgrade/candidate-54115992/templates/smoke-service/target/smoke-service-1.0.0-SNAPSHOT.jar \
bash deploy/e2e/run.sh
```

仅在预检空闲时使用 runner 既有隔离端口；只操作本轮独立 Compose project 和本轮进程，不停止或重建共享开发容器。数据库与 Redis 在本轮创建、退出时删除；浏览器串行一 worker，Maven 不并行启动其他重型任务。

原始日志及大体积证据均位于 `/private/tmp/v020-upgrade/`：

- `01-red-browser.log`、`01-red-browser-results/`：新增真实页面断言红灯，证明原流程实际加载仓内 Demo 并请求错误服务路径。
- `01-green-shell.log`：路由修复后，生成测试中的旧 `overview` 预期被正确拦住，随后同步到 `home`。
- `01-candidate-54115992.log`：干净候选打包、独立制品消费、生成工程完整构建与 17 个测试；首次 Shell 已通过，独立宿主首次被选择器点击拦截。
- `01-dev-red-results/`、`01-dev-red-results-2/`、`01-candidate-54115992-e2e-final.log`：开发宿主已真实登录，定位 Element Plus 输入层覆盖与隐藏同文案选项的测试问题。
- `01-reconsume-debug.log`、`01-reconsume.log`、`01-reconsume.py`、`candidate-reconsume-12ka43hx/`：预发布标签失败及修复后空缓存三包复消费。
- `01-release-unit.log`、`01-kit-tests.log`、`01-tools-typecheck-final.log`：直接相关回归。
- `01-candidate-54115992-e2e-settled.log`：最终两个浏览器用例通过（Shell 1/1、独立宿主 1/1），等待加载遮罩和下拉过渡退出后截图。
- `candidate-54115992/evidence/`：`package-audit.json`、`shell.png`、`dev-host.png`、`platform-final/*.log`、`test-results-final/`、`playwright-report-final/`；两张终态截图已逐张查看，未见加载 spinner、过渡残影、遮挡或裁切。平台归档来自本轮 `run-Rut5vW4y`，无需依赖即将归档的工作树。

## 验证边界与候选变化

- 实测环境：JDK 17.0.12、Node 24.14.1、npm 11.11.0、pnpm 12.4.2、macOS、Docker Desktop、Playwright 1.64.1。npm 对 stylelint 18 的 Node >=24.15 要求给出 engine 警告；本次实际相关检查均通过，未据此扩大运行时兼容承诺。
- Browser 插件未提供，使用仓库现有 Playwright/Chromium。Shell 截图为默认 1280×720，独立宿主为 1440×900；本票未调整页面视觉设计。
- 独立宿主未登录时初始订单请求会得到 401；真实登录后通过排序控件再次请求。此行为不作为已登录业务失败，也没有用 HTTP mock 替代真实后台。
- App Registry 与成员/租户/角色通过既有隔离 seed 准备，Manifest 由新服务真实上报；本票不替代 Operator 接入登记全流程专项。
- MQ 与 Nacos 在这个 HTTP 接缝关闭；Outbox 与业务事务测试不等于实际 Broker 投递。
- 本票只验收新建默认工程，不宣称已发布旧版升级、IO/storage、两个试点、完整平台质量门或发布就绪通过。
- 没有向目标 Maven/npm 仓库上传制品，没有迁移共享/线上数据库或切换环境。目标仓库只读消费在版本制品发布后按 09 执行，本票未提前执行。
- 相关制品源码变化须另建候选、比较提交与摘要并复验受影响的新建/升级/试点。消费脚本或用例变化而制品源码不变时，保留包身份，追加脚本提交与复消费/浏览器证据；本轮即按此记录。
