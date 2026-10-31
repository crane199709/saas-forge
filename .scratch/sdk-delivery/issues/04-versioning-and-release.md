# 04: 统一版本与发布流程

**What to build:** 后端可发布模块与前端 npm 包使用同一个版本号；推送版本标签时，CI 先校验版本，再跑质量门，然后发布 Maven 制品（BOM、forge-sdk-*、forge-architecture，带源码包）与 npm 包（`@forge/sdk`、`@forge/ui`、`@forge/sub-app-kit`）。仓库地址与凭证全部来自参数和 CI 密钥；私有仓库选定之前，CI 发布到任务内临时启动的本地 Maven 仓库与本地 npm registry 来验证流程。见[规格 · 统一版本与发布](../spec.md#统一版本与发布)。

**Blocked by:** 01 CI 质量门

**Status:** ready-for-human

- [x] 提供一条命令同步设置后端与前端全部版本号；发布前校验标签与各处版本一致且不是 SNAPSHOT，不一致时失败
- [x] Maven：可发布模块配置发布与源码包；平台服务（gateway、iam、platform）与 demo-service 跳过发布；发布目标地址与凭证由参数提供，构建配置中不写死地址
- [x] npm：`@forge/sub-app-kit` 取消 private，补齐 `files`、`publishConfig` 与构建脚本；各包的 `workspace:` 依赖在发布产物中替换为实际版本
- [x] 新增发布工作流：标签触发 → 版本校验 → 质量门 → 发布；未配置正式仓库密钥时发布到任务内临时本地仓库，并从中解析一次 BOM 与 npm 包，作为自检
- [x] 新增仓库根目录的变更日志，记录每个版本的变更，单列不兼容变更；首个版本 0.1.0
- [x] 验证：在临时本地仓库中，一个只引用 BOM 与 starter 坐标的空 Maven 工程能解析全部依赖；一个空 npm 工程能安装三个包且类型声明可用；平台服务 jar 不出现在本地仓库中

## Comments

### 2026-10-30 实现与本地验证

- `python3 scripts/release/version.py set 0.1.0` 同步 backend reactor 的内部坐标和前端根包、packages、apps 版本；`check v0.1.0` 检查标签一致性及非 SNAPSHOT。临时副本中验证了 0.2.1 → 0.1.0 往返无差异，标签不一致、包版本不一致、SNAPSHOT 均失败。
- Maven 发布 SDK、BOM、架构模块及解析继承链必需的两个聚合/父 POM，Java 模块附源码；服务模块跳过 deploy/source。新增架构模块的 BOM 管理条目。
- `sub-app-kit` 发布编译后的 JS CLI、类型声明及包含共享版本快照的构建配置。`pnpm pack` 生成 tarball，再由 npm 发布；发布前检查 tarball 无 private/workspace、入口存在且 CLI 为 JavaScript。
- `release.yml` 标签校验通过后复用现有三个质量工作流，再执行发布。正式仓库参数全部为空时使用临时仓库；配置不完整时失败。根目录新增 CHANGELOG.md，发布说明见 `scripts/release/README.md`。
- `scripts/release/publish.sh local v0.1.0` 完整通过：临时 file Maven 仓库与仅监听回环地址的 Verdaccio；仓库外空 Maven 工程通过 BOM 解析全部 SDK starter 与架构模块；空 npm 工程安装三个包、检查类型、加载 Vite 配置并运行编译后的 lint CLI；服务制品不存在，Java 制品带源码。日志 `/tmp/forge-release-smoke-final.log`。
- 消费验证使用独立 Maven 缓存，仅复用第三方下载并排除全部 `io.saasforge`，不会误用刚 install 的 Forge 制品。初次全冷缓存验证遇到 Maven Central TLS 中断；已通过系统 TLS 下载并校验官方摘要，未更改项目仓库地址或降低 TLS 检查。
- JDK 17 下完整 backend verify：447 项测试，0 失败/错误/跳过，含 Testcontainers、架构与格式检查。前端 typecheck、169 项单测、format:check、build、build:spike 通过；mock E2E 19/19 通过。
- 真实主链路 `deploy/e2e/run.sh` 3/3 通过，使用 0.1.0 jar 启动成功；隔离环境已自动清理。日志 `/tmp/forge-release-main-e2e.log`。
- code-review 两轴串行审查：Spec 无问题；Standards 发现启动文档仍使用 SNAPSHOT jar，已修复。主链路脚本改为动态读取 BOM 版本，避免下次升级再次失效。
- 生成器在任意仓库生成完整工程仍由工单 06 实现，镜像与模板冒烟分别属于工单 07、08。本次未推送标签或执行正式仓库发布；GitHub 标签触发、密钥配置和正式仓库消费仍待远端验收，状态为 ready-for-human。


### 真实发布后的可用性恢复

- 真实 `v0.1.0` 发布曾在 npm 上传成功后因安装元数据短暂 404 导致消费失败。补充远端发布后的有界可用性等待，检查三个包安装元数据内的精确版本及 tarball；总预算 20 分钟，认证错误直接失败，本地临时仓库不等待。
- 增加 `publish.sh consume` 和 `Verify published SDK` 手动入口，只读取既有制品，禁止将恢复验收变成同版本再次发布。输入标签继续校验全部源码版本，工作流仅使用 `packages: read`。
- HTTP/CLI 轻量回归已验证元数据/tarball 延迟、单版本端点可见但安装元数据仍不可用、缺少目标版本、超时、401/403 与版本不匹配；真实仓库消费恢复由集成工作流继续验收，本工单保持待人工验收。
