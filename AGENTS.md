## Agent

### 后端版本管理

- 后端依赖、仓库内部模块和 Maven 插件的版本统一由根项目 `pom.xml` 管理；Spring Boot 生态版本通过根 POM 继承的 `spring-boot-starter-parent` 管理。
- 子模块只声明所需依赖或插件，不得声明 `<version>`；新增非 Spring Boot 第三方版本时，必须在根 POM 的版本属性与 `dependencyManagement` 中登记。
- 如果成熟、维护活跃且许可证兼容的依赖，预计能减少至少 30% 的实现、测试与后续维护工作量，或能避免自行实现安全、协议、文件格式等高风险通用能力，可以向用户提出引入建议。建议必须说明减少的工作项、替代方案以及新增依赖的成本与风险；未经用户确认不得直接引入。工作量应基于任务拆分综合估算，不得仅以代码行数衡量。

### 数据库迁移与 Flyway

- 已合并到共享分支、已发布或无法明确证明从未执行的版本化迁移 `V<版本>__<描述>.sql`，一律视为不可变：不得修改、删除、重命名、调整版本号或通过同步修改校验和测试掩盖变更。
- 已发布迁移需要修正时，只能新增更高版本的前向迁移；仅对有明确证据证明尚未合并、发布和执行的迁移，才允许重新编号或修改。
- 遇到 checksum 不一致时，必须先对照 Git 历史、实际 SQL 和 `flyway_schema_history` 查明来源；需要保留数据时，先生成并校验数据库备份，再执行带版本、旧 checksum 和影响行数保护的精确修复。不得直接删除历史记录、关闭校验、重建数据库或盲目执行全量 `repair`。

### 配置与 Nacos

- Nacos 仅管理按环境变化的非敏感运行配置，例如服务专属的业务重试/租约/超时策略、Outbox 参数、JWT issuer 与 TTL、登录保护策略，以及 TLS 启用策略和 SSL Bundle 名称。认证、CORS、TLS 等安全边界写入 Nacos 时必须保持 `refreshEnabled=false`，只允许受控发布和滚动生效。
- `application.yaml`、Helm values 或环境变量保留应用标识和 Nacos 启动前必需的配置：`spring.config.import`、Nacos 地址/namespace/客户端 TLS、工作负载身份，以及不能由 Nacos 管理的部署拓扑参数。MyBatis 映射、Jackson 行为、Flyway 开关等制品固定配置不迁入 Nacos。
- 密码、Token、数据库/Redis/Kafka 凭据、OAuth Client Secret、JWT/KMS 私钥、mTLS 证书与私钥不得写入 Nacos 或仓库普通配置；只能由外部密钥管理、Secret 或受限凭据文件注入。Nacos 中可配置 SSL Bundle 名称，不能配置其证书材料。
- `browser.rootDomain` 及由其推导的 CORS/Cookie 安全边界属于部署配置，不迁入 Nacos；服务 HTTP/gRPC 实例地址不得仅以 Nacos YAML 中的静态地址替代服务发现。
- Nacos 配置按环境 namespace 和应用专属资源维护，当前不得新增共享资源。跨服务必须一致的非敏感值应分别写入各自资源，并增加或更新一致性校验；迁移前必须先确认每个环境的实际值，不能臆造 staging/prod 值。
- 修改 `deploy/nacos/<environment>/` 后必须递增对应 `configuration-revision`，运行 `bash scripts/validate-nacos-config.sh` 与相关服务验证；发布只能通过受控发布流程，Console 应急变更必须回写 Git。

### Console 与浏览器 HTTP

- HttpOnly Cookie、`Origin` 与 `Sec-Fetch-*` 是由浏览器管理的安全边界，不得暴露为 Console 或 Remote 的业务调用参数；消费者只能通过共享类型化 HTTP Client 调用正式 API operation，不得自行注入 Cookie、Origin、Fetch Metadata 或 Bearer Token。

### 本地开发

- 日常开发以原生启动为目标：前端在应用目录执行 `pnpm run dev`，后端由 IDE 直接 Run/Debug Spring Boot 启动类；不得将后台进程托管、打包 JAR、`replace/restore` 或完整 Compose 编排设为日常应用启停的必经步骤。该规范是开发流程改造的验收目标，不表示现有入口已全部支持。
- 环境初始化、依赖准备与应用进程生命周期分离；允许首次初始化证书、域名和配置，以及独立启动必要基础设施。支持同时运行多个本机服务，同一服务本机与容器实例的冲突由开发者处理，不新增自动接管、替换或恢复机制。
- 本地运行配置由开发者在 Git 忽略的文件中自行维护，仓库不提供可提交的配置模板；数据库、Redis、Kafka、Nacos 等依赖地址可自行配置，不强制使用本机容器或共享环境。敏感值仍遵循既有 Secret、环境变量及受限凭据文件规则。
- 仅本地开发允许使用文件提供非敏感运行配置，替代 Nacos 配置中心；需要跨服务联调时仍统一使用 Nacos 服务发现，不新增通过本地配置指定下游 HTTP/gRPC 实例地址的替代方案。该例外不改变测试、生产的配置发布和服务发现机制。
- 原生启动不改变 Local Browser Topology：浏览器仍使用受信 HTTPS、受控域名和 Gateway，保持既有 Cookie、CORS、CSRF 与 Browser Session Slot 边界；内部进程监听地址不作为替代浏览器入口。
- 现有完整环境和验收脚本保留用于集成验收，退出默认本地开发流程；不得只把旧托管脚本包装成 `pnpm run dev` 就视为完成原生启动。

### 当前开发阶段兼容范围

- 产品仅承诺桌面版 Google Chrome 当前稳定版和 JDK 17；暂不承担旧版或移动端 Chrome、Edge、Firefox、Safari、其他 Chromium 浏览器及 JDK 21 的适配与验收要求。
- Chromium 保留为日常功能与视觉测试工具，不形成独立产品兼容承诺；必要的真实产品与四域安全验收使用 Chrome。本地、CI、发布和未完成 Issue/PRD 的兼容矩阵统一按此范围执行，历史验收记录保留原事实。
- 仅收缩兼容矩阵，不删除功能、键盘、无障碍、国际化、视觉、安全、契约、迁移与 Fresh Compose 等必要检查；移动或合并 CI 任务必须保留其中的独有检查及失败传播。
- 首次对外发布前重新评估支持范围，不自动恢复旧矩阵；新增浏览器或 JDK 支持须经用户明确确认。决策见 [ADR 0046](docs/adr/0046-development-supports-chrome-and-jdk17.md)。

### 验证范围与资源

- 普通改动优先执行受影响模块的检查及必要集成验证，并说明选择依据；不得把完整 Compose、fresh 环境、多浏览器或五服务替换矩阵作为每个 ticket 的默认本机门禁。
- 认证、跨服务契约、数据库迁移等改动应扩大到对应边界；本文件已有 Flyway、Nacos、国际化等专项验证要求继续有效。具体 Issue/PRD 明确要求的本地或完整验收不得静默省略，兼容范围按上述阶段性约定执行。
- 完整验证由 CI 承担，本机保留复现能力；专项任务按其验收标准执行。调整 CI 重复路径时保留必要的浏览器、JDK、安全和 fresh 环境覆盖，不以删掉独有验证降低耗时。
- 日常必要验证以 5 分钟内反馈为优化目标，优先保证验证期间电脑仍可正常开发；目标冲突时允许延长耗时。避免无依据地并行启动重型验证，未实测不得承诺耗时、资源占用或改善比例。
- 验证记录区分通过、失败、跳过和未执行；本地相关检查通过不等于完整验收通过，配置文件检查或进程启动成功也不等于联调成功。

### 前端仓库与历史检查

- 正式统一 Console 在独立 `saas-forge-web` 仓库维护；本仓 `consoles/` 已迁出，后端构建和 CI 不读取前端源码或运行浏览器。
- 修改跨仓验证、前端迁移或旧验收入口时，先读 [迁出记录与待迁检查](docs/acceptance/consoles-extraction.md)。本机备份不是前端交付物；未迁业务、安全、国际化、视觉和无障碍覆盖继续由 #206 及对应业务票跟踪，不能因旧入口移除而勾选通过。

### 文档与证据的权威顺序

本仓库的文档跨越了多次实现变更，**文档描述的是目标，不一定是现状**。判断"当前实现了什么"时按以下顺序取证，冲突时以序号小的为准：

1. **代码与配置**：根 `pom.xml` 与各模块 `pom.xml`、Flyway 迁移 SQL、`saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml`、各 `package.json`、`compose.yaml`、`.github/workflows/`。
2. **`CONTEXT-MAP.md`、各 `CONTEXT.md` 与 `docs/adr/`**：领域语言与已生效的决策。
3. **`README.md` 的「当前状态」段与最新验收记录**：实现进度与实测结果。
4. **`docs/NN-*.md` 设计文档**：设计基线与约束，不代表已实现；文首状态横幅会指出已知失效范围。
5. **`docs/archive/` 与 `docs/audits/`**：前者是载体已删除的历史文档，后者是对仓库的时点体检报告。两者**都只能用于追溯，不得作为实现或勾选依据**。

引用任何包名、目录或脚本前，先确认它在工作区中真实存在。文档中的前端包名（如 `@saas-forge/design-system`）、依赖版本、浏览器矩阵与门禁计数最容易过期。

### 工作区与临时文件

- `.scratch/` 是**本地临时工作区**，已被 `.gitignore` 忽略，用于放中间笔记、本机验证产物与提交前审计报告。
- `.scratch/` 中的内容不属于交付物，**不得作为验收证据引用**，也不得假设其他环境或后续会话仍能读到；需要长期保留的结论必须落到 `docs/`（审计报告放 `docs/audits/`）。
- 构建产物（`target/`、`dist/`、`node_modules/`）同样不提交。新增忽略规则时，先确认被覆盖的路径没有已跟踪文件：`git check-ignore` 只判断规则，`git ls-files` 才能证明未被跟踪。

### Issue tracker

问题与 PRD 通过本仓库的 GitHub Issues 跟踪。详见 `docs/agents/issue-tracker.md`。

### Domain docs

本仓库采用多上下文领域文档布局，并以 `CONTEXT-MAP.md` 为入口。详见 `docs/agents/domain.md`。
