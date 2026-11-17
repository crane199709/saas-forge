# 工单 07：AI Platform 实际升级验收

日期：2026-11-17。工单：[07](issues/07-ai-platform-upgrade.md)。当前固定 **0.2.0-SNAPSHOT 阶段全部通过**。最终 `0.2.0` 坐标候选由 08 建立后，AI 分支仍须更新父版本、三包与锁文件并复验；本记录不代表最终坐标或发布就绪。

## 基线与候选

- AI 仓库起点：`c5cc07fdf396bc9098785edc5fd96d77a5f6ca6c`，原 main 与新隔离工作树均干净；AI 分支 `codex/v020-upgrade-ai`，阶段交付提交 `62f3182211207d3441238568e5ae78d6f14cb3da`，完整全栈源码为 `4a010916e8e644541a4307edf6e15b23bd8e862a`（后续仅视觉 PNG 与文档）。其他会话工作树没有改动。
- 平台分支：`codex/v020-upgrade-07`；实施起点集成 `39643068`，随后合入 `0ad3e158`。交付前已合入集成 `5bf08352a06296f0cdc22745dd978f279427d34a`；其 SDK/前端包源码与候选 `54115992` 差异为空。
- 原 Java 父版本与前端 Forge 坐标均已为 `0.2.0-SNAPSHOT`，不是已发布 `0.1.0`。原前端使用根 workspace 的 `file:.forge-packages/*.tgz` 替代，README 标记来源 `5a6fd0fa`；原 Java 快照没有固定制品摘要，本机 install 缓存不能作为发布来源。
- 候选制品源码：`54115992e00691be57b912e46593709c77c344a1`；坐标仍是未发布的 `0.2.0-SNAPSHOT`。目录 `/private/tmp/v020-upgrade/candidate-54115992/`，清单 SHA-256 `999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。候选没有重建或改包。
- AI 本次直接使用 scheduling，没有 IO/storage；不加入未使用的可选能力。业务自身版本 `ai-service:0.1.0-SNAPSHOT`、Sub-App `0.1.0`、Manifest `8` 保持原值。

| npm 原包 | 候选 SHA-256 | 实际处理 |
| --- | --- | --- |
| SDK | `db3b4496a9691ca718b19e7dc95a9055ff242b63b6622c35a52a2cf024cee890` | 原包已相同，保留 |
| UI | `bf5f7c012df8e86e1528b2b0435f9a8a4ad933bea9c13f280d8fdbba58bc653c` | 替换原包并刷新锁记录 |
| kit | `a9c964e4aad3e18fb288051e823f7715ef894f51f5e1f4ed882a39a45863cc3d` | 替换原包并刷新锁记录 |

## 实际改动与保留

1. 前端固定上述实际 tarball，并在 AI 的 `.forge-packages/candidate.json` 记录候选来源与摘要。显式 `pnpm update ... --lockfile-only` 后再 frozen install，逐文件核对安装内容与候选包完全一致；仅普通 install 曾继续使用同名 `file:` 包的旧缓存。
2. 从候选真实生成工程同步 Sub-App 规范及主题 Agent 说明。既有共享依赖精确版本、统一 `/api` 入口、移除 `apiPrefixes`、后端 Ops/Bean Validation/NeedBraces/中间件门禁与 scheduling IAM 引用均已存在，按真实差异保留，没有机械重生成工程。
3. 新增 `scripts/verify-candidate-sdk.sh`：核对固定清单和全部制品，创建不含本机 Forge 的 Maven 缓存，将 `forge-release` 定向到候选 file 仓库，执行完整 verify 和权限目录门禁；58 个解析后的 JAR/POM 文件（含时间戳与 SNAPSHOT 别名）全部匹配候选摘要。实际可执行 AI JAR 中的 12 个 Forge lib 也逐个匹配。
4. Vite 显式添加 `gateway: process.env.FORGE_DEV_GATEWAY`，使独立宿主消费本轮隔离网关，未设置时沿用 kit 默认；仅传环境变量而未接选项会继续访问 `localhost:8080`，真实 `/iam/auth/login` 代理连接拒绝已作为红灯保留。
5. 复用既有全栈产品测试，加上独立宿主真实登录、业务 HTTP 与主题用例。先证明仍加载 Shell 的旧接缝准确红灯，再在 Shell 验收后关闭本轮 Shell，以相同 IAM 回调源启动 AI `pnpm dev`。没有放宽源校验或生产配置。开发宿主自动选首个 membership，验收使用既有单 A 租户的 `ai-reader` 并先断言真实租户；Shell 保留 A→B→A→无授权租户的边界。Vite 选项改动对 13 个生产 dist 文件的字节差异为零。
6. 187 个业务 `src/main`/`src` 文件（包括后端 application.yml 与全部迁移；Vite 的必要代理选项见前项）SHA-256 保持原值。原开发库仅以现有配置进入 JDBC **只读事务**读取 Flyway 历史后回滚：V1、V4、V6—V12 共九条均成功，V12 已在实施前完成（原始 installed_on 值见证据）。本次没有对共享库运行迁移或切换服务；隔离数据库按保留历史成功迁移至 V12，SDK 表与注释检查通过，无需新增迁移。

## 验证结果

| 接缝 | 结果 |
| --- | --- |
| 固定候选 Maven | 完整 `verify` 19/19；权限目录通过；所有解析和实际打包 Forge lib 摘要匹配 |
| 平台产品 HTTP/MQ | `PlatformIntegrationIT` 56/56；真实 IAM、Gateway、AI、PostgreSQL、Redis、RocketMQ；权限、租户、对话、配置与用量回归通过 |
| scheduling | 候选 `TaskRunner` 实际注册并完成 `ai-conversation-retention`；三个既有产品用例验证到期会话清空且保留新历史、恢复遗留活跃标记、停用/过期 IAM 租户扫描与 Plan/Tenant 保留期优先级 |
| Runner | 30/30；真实 `@deepseek-ai/dsh@0.2.2-rc.1` 公开 HTTP 契约，覆盖流式、工具、取消/崩溃恢复、准入及隔离 |
| Sub-App | Vitest 14/14，格式、隔离 lint、stylelint、类型和构建通过；实际安装的三包全部文件匹配原始候选 |
| 配套 Shell 与独立宿主 | 最终完整 `FullStackIT` 3/3；Shell 两宽度与平台模型控制台浏览器 3/3；独立宿主 1440/1024 浏览器 2/2，真实登录 A、模型读取 200、会话创建 201、主题与零相关控制台错误通过 |
| 视觉基线 | 旧主题红灯保留；30 张 PNG 按统一主题更新并逐张走查，更新与最终比较均 26/26。独立宿主在弹窗遮罩完全关闭后重新取证并逐张查看，无残影、遮挡或裁切 |

配套 IAM/Platform/Gateway JAR 复用 01 的真实构建，已复制到本票证据的 `runtime-m2/`。它们的来源与摘要在 `runtime-provenance.json`；配套 Shell 源码为 `e33dd537188339e634507e20689d8c4183bd931d`，相关服务、SDK、前端源码与 `54115992` 差异为空。业务候选缓存与运行包仓库分开，AI `check-fullstack.sh` 没有用 Java 源码 install 覆盖候选依赖。

## 复现与证据

原始证据统一保存 `/private/tmp/v020-upgrade/07-ai/`，不依赖即将归档的 AI 工作树目标目录：

- `context.md`、`baseline.json`、`baseline-flyway.tsv`、`original-local-maven-cache.json`、`preservation.json`：基线、实际历史与保留校验。
- `red-source.log`：候选入口拒绝原 kit 原包。`green-maven.log`、`green-maven/resolved-forge.json`、`boot-classpath.json`、`ai-service-0.1.0-SNAPSHOT.jar`：真实候选消费及实际可执行包。
- `npm-resolved.json`、`pnpm-relock.log`、`pnpm-install-candidate.log`：同名快照锁刷新与真实安装内容检查。
- `platform.log`、`platform-summary.json`、`platform-surefire-reports/`、`platform-platform-it/`：56 项平台产品结果和配套进程日志/运行包摘要。
- `runner.log`、`sub-app-test.log`、`sub-app-build.log`、`format-green.log`、`validate-final.log`：直接相关检查。
- `fullstack-red.log`、`fullstack-red/`、`visual-red.log`、`visual-red-results/`：接缝及旧主题基线红灯；`fullstack-green.log` 保留就绪探测 HTTP/2 超时诊断，后按既有方式改 HTTP/1.1 与短超时；`fullstack-final/` 保留缺 gateway 选项的代理连接拒绝，`fullstack-settled/` 保留多租户账号首选无授权 C 的 fixture 红灯。
- `fullstack-final-screens.log`、`fullstack-final-screens/`、`fullstack-summary.json`、`final-surefire-reports/`、`final-platform-it/`：最终完整通过、源码身份、原图和进程日志。`visual-update.log`、`visual-final.log`、`visual-baselines/`、`visual-review/`：30 张 PNG、走查联系表与最终比较。`gateway-dist-preservation.json` 证明生产产物未变。

在 AI 仓库运行固定候选验收（每次新证据目录），再复用其独立缓存；配套平台 JAR 与已安装前端依赖的源码目录另行提供：

```sh
bash scripts/verify-candidate-sdk.sh /path/to/candidate /tmp/new-ai-candidate
AI_MAVEN_OFFLINE=true AI_MAVEN_REPOSITORY=/tmp/new-ai-candidate/m2 \
AI_FORGE_RUNTIME_REPOSITORY=/path/to/matching-runtime-m2 \
AI_FORGE_SOURCE=/path/to/matching-forge-source AI_E2E_OUTPUT=/tmp/new-ai-fullstack \
  bash scripts/check-fullstack.sh
```

完整容器/浏览器测试在本次 16GiB 机器上由 `python3 /private/tmp/v020-upgrade/run-heavy.py <命令...>` 串行调度，每次只排一项，入口内部不嵌套锁。Browser 插件未提供，使用项目现有 Playwright/Chromium。实测 JDK17.0.12、Node24.14.1、pnpm12.4.2。

## 边界与回填

- 模型与外部 MCP 使用项目既有的本地确定性 HTTPS 提供方；IAM、Gateway、AI、DB/Redis/MQ 与真实 Runner/DSH 实际运行。没有真实供应商计费外呼，不把 HTTP 边界替代称为供应商线上联调。
- 普通不连接 IAM 的快速测试会记录首次保留期任务连接拒绝；完整隔离平台使用真实 IAM，TaskRunner 完成，三个保留期产品回归通过。平台测试中的 `TenantDeleted` 重试失败为既有故障注入用例，最终 56 项全部通过。
- 未发布目标 Maven/npm 制品，未执行生产/共享数据库迁移或环境切换，未运行远端 CI。AI 常规 CI 使用目标 Maven 快照，本次候选是单独固定的未发布输入；发布后仍需 09 的目标仓库只读复验。
- 已回填[前端通用清单](../../docs/upgrading-sub-app-v020.md)中的同名 `file:` 快照刷新，以及[后端通用清单](../../docs/upgrades/business-service-0.2.0.md)中的实际快照基线、配套运行包与候选依赖隔离、按既有 scheduling 业务验收。没有新增平台规则或默认必需项。

## 最终坐标阶段交接

08 将在隔离干净源码上设置最终 `0.2.0` 坐标并重新打候选；集成分支仍保持 SNAPSHOT。届时必须更新 AI 升级分支并复验受影响路径，不能复用本票结果充作最终坐标证明。最小版本改点、脚本位置与复验命令已交接到 `/private/tmp/v020-upgrade/07-ai/context.md`。本票 resolved 仅表示当前固定 SNAPSHOT 阶段完成；候选变化须更新相应记录。
