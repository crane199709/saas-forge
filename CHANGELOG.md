# 变更日志

后端 Maven 制品与前端 npm 包使用同一语义版本。1.0 之前的次版本可包含不兼容变更，升级前请检查本节。

## 0.2.0（未发布）

### 变更

- Java 的 `if`、`else`、`for`、`while`、`do-while` 语句体一律使用大括号（含单条语句与空循环体）。`forge-dependencies` 在 `validate` 阶段自动运行 Checkstyle `NeedBraces`，覆盖生产与测试代码，违规即失败；平台、SDK 与 archetype 生成的业务服务共用内联规则。同步根与业务服务模板的 `AGENTS.md`、业务服务编码规范、平台内部规范、接入指南，以及 Sub-App 规范与模板中的配套后端说明。已有服务升级父 POM 后须补齐大括号，并更新规范副本与 `AGENTS.md`；前端检查规则不变。
- 字段脱敏与权限目录、SDK 依赖门禁集成：将可选日志适配 `ch.qos.logback:logback-classic` 纳入 Security 模块直接依赖白名单，按独立授权和审计用途登记 `viewRaw` 动作，权限目录随当前源码更新；保留所有原有中间件禁用与白名单约束。

- `forge-dependencies` 新增 `validate` 阶段的中间件直引门禁，禁止 Spring Data Redis、Redisson、Lettuce、Jedis、RocketMQ/Nacos 原生客户端与 XXL-JOB；Boot Redis、Spring Cloud Nacos 及 Forge MQ / Scheduling starter 入口保留，传递依赖不受影响。仅 SDK MQ 与 Reference Data 按需显式豁免，平台三个服务与其他 SDK 模块继续受约束。业务服务规范新增「中间件依赖入口」，archetype、sub-app-kit 配套规范与接入指南同步。已有业务工程升级父 POM 后自动启用；直引原生客户端的工程先改用相应 starter，再按 CHANGELOG 重新复制 `docs/standards/`。

- 在线敏感字段接入统一 Jackson 3 模块，按注解与 IAM Manifest 路径/分级匹配，嵌套对象、集合与 null 都校验规则；整个 JSON 响应由实际转换器完成并缓冲后发送，保留其视图、过滤器、声明类型与 writer 定制，末尾字段失败也返回完整 500。demo 清单升 8，增加在线/Excel/日志共用的敏感字段示例，以及独立授权并审计的原文查看。新增 ArchUnit 规则拒绝业务自有在线 serializer 与常见手写掩码，普通字符串格式化仍允许；`docs/standards/`、archetype 配置提示、sub-app-kit 生成规则、业务接入指南及 E2E 配置同步。

- `ExcelIo.export` 按敏感列登记路径复用统一引擎，每任务一次不可变快照，零行也预检缺规则与 L4，失败以 `io.maskingFailed` 结束且不上传结果文件，导出成功与失败审计均省略可能含原文的查询摘要。`forge-sdk-security` 在 Boot 日志初始化前登记本地声明与 Logback `%m`/`%msg`/`%message`/`%kvp` converter，支持命名占位参数、结构化 key/value 与 `SensitiveLogValue.of`；包装对象字符串化始终安全。日志按事件 MDC 读取租户，独立一秒有效快照异步刷新，首次、过期、不可用或失败时丢值并写非递归 status 告警；不阻断业务，关闭应用释放线程和运行时。新增业务规范「敏感字段导出与日志」，同步 archetype、sub-app-kit 生成规则、子应用规范与服务指南；旧服务不新增必配项，SDK API 随未发布 `0.2.0-SNAPSHOT` 发布。

- Shell 新增租户端与运营端数据安全页面，IAM 新增数据安全：平台固定 L1–L4（L4 禁止导出）、九种预置策略规则及 CRUD/预览，租户可为 Manifest 敏感字段选择或恢复规则；按租户覆盖 > 字段默认 > 分级默认解析。新增独立读/管理权限码并升级 IAM 清单版本，规则和登记变更写不含原文的审计；强制租户 RLS 与全租户引用检查、统一事务锁防止并发删除被引用规则。`forge-sdk-security` 新增无缓存 IAM HTTP 策略提供方；业务规范新增可选运行时配置：显式启用 `forge.field-masking.enabled=true` 时必须提供 `iam-uri` 与 `token`，旧服务不新增必配项。archetype 配置说明、sub-app-kit 生成说明与配套规范、业务服务指南同步，前端不得持有服务凭证。API 随未发布 `0.2.0-SNAPSHOT` 发布。

- `forge-sdk-security` 新增固定 `MaskingRule`、纯 `MaskingEngine.validate/apply/preview`、安全预览结果与通道共用的生效字段策略端口。九种策略统一按 Unicode 码点保留字符，严格拒绝未知策略和无关参数；固定保留范围覆盖短值时全部掩码，`keepFirst` 保留整值及所有非空未改变结果失败，异常不包含原文。新增 API 保持现有调用兼容，随当前未发布 `0.2.0-SNAPSHOT` 发布；未新增配置、业务编码规则或脚手架必需项。

- Resource Manifest 新增可选 `sensitiveFields`（路径、L1–L4 分级、可选默认规则），SDK 提供 `@SensitiveField` 并在 CLI/启动生成时合并字段与 getter 声明，上报前再次校验；重复路径、非法路径与分级拒绝。业务规范新增「敏感字段登记」，声明变化须升 `forge.manifest.version`；空声明保持旧清单内容兼容，不增加必配项。archetype 带空声明与版本提示，sub-app-kit 配套规范新增服务端脱敏值展示规则，生成的 AGENTS 摘要与服务接入指南同步。SDK 保持当前 `0.2.0-SNAPSHOT` 开发版本。

- **破坏性**：IAM、Platform、Demo 服务端统一使用 `FORGE_OPS_TOKEN`，Platform/Demo 的清单及 IAM 目录访问统一使用 `FORGE_MANIFEST_TOKEN`，移除本地静态默认凭证。Platform 不再内置服务令牌列表，完整映射通过专属 `forge-platform.yaml` 提供。新增独立服务环境文件与映射样例；本地启动、隔离 E2E、业务服务规范、archetype、sub-app-kit 配套规范、中英文 README 同步。既有环境须保留原值迁移到各服务私有文件，不能共用通用变量文件；IAM 已弃用的静态引导 Map 仍保留兼容。

- SDK Manifest 上报改为异步初次 + 三次退避重试，之后每五分钟最多补报十二次，成功/关闭即停止；增加结构化失败分类及独立 Ops Token 保护的 `/internal/onboarding-status`。平台新增 `platform:onboarding:read` 与只读“服务接入诊断”：读取 Nacos 客户端配置、lb 实例状态及真实网关 OpenAPI 身份证据；新增必配网关 origin，应用配置与隔离 E2E 地址同步。SDK OpenAPI 输出 `x-forge-service`。`docs/standards/business-service.md` 新增启动上报与内部状态规则：Servlet 服务引入 manifest 也须配置独立 Ops Token（包括关闭上报时），脚手架已具备该配置；服务指南与 archetype 状态说明同步，archetype 构建携带更新后的业务服务规范。前端设计规则不变。
- 平台控制台新增服务凭证登记、一次性环境变量片段复制、平滑轮换引导与立即停用；新增 `platform:service-credential:manage`。平台经独立 IAM Ops Token 和服务发现访问内部管理接口，操作人从验证后的 Operator 上下文提供，IAM 统一记录生命周期审计。凭证业务错误码统一为 `service-credential.*`。

- **破坏性**：前端 SDK `api()` / `page()` 强制添加浏览器 `/api` 入口；Shell 开发 Vite、生产 Nginx 与 sub-app-kit 独立宿主固定代理 `/api`（剥离入口段）与 `/iam`（认证透传）。移除 `FORGE_API_PREFIXES` 与 `apiPrefixes`，不保留逐服务前缀兼容代理。升级时配套部署 Shell、SDK、sub-app-kit，已生成 Sub-App 删除 `apiPrefixes`；业务源码路径、网关路由、认证路径与 Cookie 语义保持原样。`docs/standards/sub-app.md` 新增「接口入口」，`business-service.md` 补充浏览器与直连网关路径区别；两套脚手架随构建携带新规范，Sub-App 模板与视觉用例同步。新增服务前端代理改动为 0 处。
- IAM 服务凭证改为数据库管理状态：支持同服务多凭证、逐条立即停用及原子紧急轮换，库内只存 SHA-256 摘要，生命周期与受限流保护的认证失败写 Platform Realm 审计；内部认证每请求查库，数据库不可用返回 503。`forge.iam.manifest.tokens` 降级为可空且已弃用的静态引导通道，移除应用自带默认凭证，已有本地环境需登记凭证或显式提供引导值。接入清单、服务指南和 archetype 的 `FORGE_MANIFEST_TOKEN` 说明同步更新；业务服务配置键不变。

- 发布后源码立即进入下一个 `-SNAPSHOT` 开发版本，同一正式版本号只对应一份发布内容：`version.py set` 接受 `-SNAPSHOT`，新增 `version.py dev` 由分支 CI（Backend quality）检查源码处于统一的 SNAPSHOT 版本；`check` 与正式发布仍拒绝 SNAPSHOT，只有 `publish.sh local`（模板冒烟）以 `--allow-snapshot` 部署当前源码到临时仓库。版本同步范围新增 `@crane199709/sdk` 的 `sdkRuntime` 版本。当前源码为 `0.2.0-SNAPSHOT`。
- 后端版本只维护在 `backend/.mvn/maven.config` 的 `revision`：POM 中的 Forge 坐标与 `forge.version` 改为 `${revision}`，由 `flatten-maven-plugin` 在安装与发布时解析为实际版本；发布的制品坐标不变，仓库外工程无需调整。
- archetype 生成的主类名由 `serviceName` 派生（`orders-service` → `OrdersApplication`），不再固定为 `DemoApplication`。
- archetype 生成的工程自带 Maven Wrapper（与平台仓库同一份，`mvnw` 可执行），`AGENTS.md` 与接入指南的命令统一为 `./mvnw`。
- archetype 迁移由八个演进版本合并为两份：`V1__forge_sdk.sql`（SDK 必需表）与 `V2__example_orders.sql`（示例业务表，删除示例时整体删除）。表结构与原八个迁移执行后的结果一致。
- `forge-create-sub-app` 在仓库外生成时提示 `pnpm dev` / `pnpm build`；只有 `--workspace` 模式提示 `pnpm --filter`。
- Sub-App 脚手架按应用编码和端口新增 `pnpm start:<应用编码>`：先构建，再以 `127.0.0.1:<端口>` 预览，供租户 Shell 加载；生成后的命令提示、`AGENTS.md` 与接入指南同步说明。已生成工程需自行将 `"start:<应用编码>": "pnpm build && pnpm preview --host 127.0.0.1 --port <端口>"` 加入 `package.json` 的 `scripts`。
- 规范 `business-service.md`「禁止」一节的 JDBC 规则新增例外：代 Tenant 连接不属于平台的外部数据库时可用原生 JDBC。`ForgeArchitectureTest` 新增可覆盖的 `externalJdbcClasses()`，`ForgeArchitectureRules` 新增 `persistence(Set<String>)`，只对列出的类（含内部类）放开 JDBC 依赖检查，SQL 注解、字符串 Wrapper 与 SQL 片段限制不变。已生成的工程如需此例外，重新复制 `docs/standards/` 并在 `ArchitectureTest` 中覆盖该方法。
- 脚手架带入代码格式化：Spotless 规则从 `forge-parent` 移入 `forge-dependencies` 的 pluginManagement（平台仓库生效配置不变），archetype 生成的工程声明 Spotless 插件并带 `spotless-xml.prefs`，`verify` 末尾检查格式；`forge-create-sub-app` 生成的工程复制平台的 `.prettierrc.json`，新增 `prettier` 依赖与 `pnpm format` / `pnpm format:check` 脚本，`.prettierignore` 排除 `docs/standards/`。模板已按默认示例排版，包名或应用编码较长时生成后执行一次 `./mvnw spotless:apply` / `pnpm format`。规范 `business-service.md` 与 `sub-app.md` 各新增「代码格式」一节。已生成的工程如需接入：后端在 POM `build/plugins` 加 `com.diffplug.spotless:spotless-maven-plugin`（不写版本与配置）并从新生成的工程复制 `spotless-xml.prefs`，执行一次 `./mvnw spotless:apply`；前端复制 `.prettierrc.json`、`.prettierignore`，合入 `prettier` 依赖与两个脚本后执行 `pnpm format`；两者都重新复制 `docs/standards/`。
- 已生成的工程：主类名、Wrapper 可按需自行调整或从新生成的临时工程复制；**不要**把合并后的迁移复制进已执行过旧迁移的工程，Flyway 历史以已执行的版本为准。

## 0.1.0

### 新增

- 脚手架带入规范：archetype 生成的 Business Service 与 `forge-create-sub-app` 生成的 Sub-App 新增 `AGENTS.md`（规则摘要、构建 / 测试 / lint 命令）和 `docs/standards/`（`business-service.md` 或 `sub-app.md`，头部注明平台版本）。规范只在平台仓库 `docs/standards/` 维护一份，生成时复制。
- archetype 模板请求体补 `@Valid` 校验，`business-service.md`「接口格式」新增规则：请求体加 `@Valid`，违反约束由 SDK 返回 400 `request.invalid`；`SchemaCommentTest` 检查 POM 中每个 SDK starter 自带 `schema.sql` 的表都已迁移。
- Sub-App 模板首页改为 `PageHeader → PageToolbar → ListPage` 列表原型，字段与工具栏排序只用 archetype 订单接口提供的字段和可排序字段；新增导出 `@crane199709/sub-app-kit/stylelint`，生成工程带 `pnpm lint:style`（接入 `build`）与 `playwright.visual.config.ts`、`e2e/visual/` 示例视觉用例（不带基线截图，首次运行 `pnpm test:visual:update` 生成并提交）。模板冒烟增加 `pnpm lint:style`，视觉测试不进冒烟。
- 已生成的工程不会自动更新，按需从新版本复制：用同版本 archetype 或 `forge-create-sub-app` 生成一个临时工程，把其中的 `AGENTS.md`、`docs/standards/` 复制到本工程（`AGENTS.md` 中的服务名、命令按本工程调整）。后端另把临时工程 `SchemaCommentTest` 与 `SchemaCommentMapper`（含 XML）的 SDK 表检查合入，并在 POM 加 `spring-boot-starter-validation`、请求体加 `@Valid`；前端另合入 `package.json` 的 `lint:style`、`test:visual`、`test:visual:update` 脚本及 `stylelint`、`postcss-html`、`@playwright/test` 依赖，复制 `stylelint.config.mjs`、`playwright.visual.config.ts` 与 `e2e/visual/`。以后通用规范有改动时，本节写明改了哪些规则，届时重新复制 `docs/standards/`。

- SDK 导入校验成功后进入 `AWAITING_CONFIRM`，显示将写入的行数；提交人通过 `ExcelIo.confirmImport` 确认后，任意实例从持久源文件完整重新校验并事务写入，失败提供新错误报告且零写入。待确认不占队列、不判中断，24 小时后失败并清理源文件。

- 对象存储支持 `StoragePurpose`：服务声明扩展名、大小与下载时长，SDK 统一校验并记录用途；IO 源文件、结果与订单附件分别限定用途，上传拒绝原因沿用前端 `userTip` 展示。

- SDK 新增资源恢复的共享错误码和中英文提示，`ListPage` 可选已删除视图及恢复事件；Business Service 接入指南补充恢复规则与验证要求。
- 角色、部门、岗位和 demo 订单支持查看已删除记录并恢复：保留角色配置，部门按当前父路径恢复，沿用原管理权限、租户与数据权限，以及现有唯一性规则。

- IAM 新增共享 Todo 状态索引与当前成员分页查询：按业务发生时间抵御重复、乱序事件，实时按数据库角色权限与 Plan 裁剪；demo-service 创建订单开启待办，关单、删单及超时关闭同步结束待办。
- IAM 新增按月分区的 Notification 收件箱，支持创建时 Permission 收件快照、分页、已读与未读计数，按 180 天整月清理；demo 订单创建事务发送真实站内通知。

- 新增 `forge-sdk-notification`：在业务事务内发送 Notification、开启和关闭 Todo，提供目标、站内链接与发生时间事件契约；经既有 Outbox 投递到 `forge-notification`，无需新增 SDK 表。

- 平台控制台新增任务运行入口，按服务查看规则、最近运行和问题运行；支持确认后手动触发与失败租户单独重跑，并提示历史留存边界、失败明细上限及运行冲突。

- 支持 Operator 按服务查看任务声明、调度规则和最近运行，异步手动触发或单租户重跑；复用运维令牌通道与读/管理权限，平台记录成功/失败审计。
- 定时任务自动在 Redis 保留最近运行与最近非成功运行，提供 `TaskRunHistory` 查询；记录租户结果、有界失败明细和安全错误摘要，并识别崩溃/失锁后的中断，无需数据库迁移。

- 新增 `forge-sdk-ops` 独立令牌运维通道，平台从 IAM 服务目录经负载均衡调用，并按服务标记运维能力可用状态。
- Dead Event 支持按服务分页查询、填写原因后直接重放或放弃；平台校验运维权限并记录成功/失败审计，接口只返回元数据。SDK 自动清理处置超过 90 天的记录，并随租户最终删除清理其死信。
- MQ 消费重试耗尽时独立事务捕获 Dead Event，按消费组与事件 ID 幂等保存；增加死信新增计数与 Outbox 未发送积压指标。已有服务升级时须添加 `forge_dead_event` 迁移，并保证 RocketMQ 消费组重试次数不小于 `forge.mq.max-delivery-attempts`（默认 16）。

- 首次交付 Forge BOM、按能力拆分的 SDK starter 与共享架构规则，Java 模块附带源码包。
- 发布 `@crane199709/sdk`、`@crane199709/ui`、`@crane199709/sub-app-kit`；提供类型声明与编译后的 CLI。
- 提供统一版本命令、标签发布质量门和临时 Maven/npm 仓库消费自检。
- 提供 Business Service archetype、接入指南和可在仓库外运行的 Sub-App 生成器；BOM 的 SDK 版本独立于业务工程版本。
- 提供四个后端服务与两个 Shell 的镜像构建入口，以及从临时发布到仓库外生成、启动和权限校验的模板冒烟质量门。

### 不兼容变更

- 前端 `IoTaskApi.list(page)` 现在接收从 1 开始的页码并返回 SDK `Page<IoTask>`；调用方须转发页码并保留分页元数据，不能只返回 `items`。导入与导出记录可翻页，避免较早待确认任务被最新十条记录遮蔽。

- `ExcelIo.importFile` 改为三参 `(model, source, validate)`，不再自动写入；Business Service 必须提供有权限控制的确认接口，重新传入模型、校验器与 writer 调用 `confirmImport(taskId, model, validate, writer)`。旧四参 `importFile` 签名删除。既有 IO 表无需新增迁移。

- `ObjectStorage.prepareUpload`、`write`、`prepareDownload` 删除无用途重载，须以 `StoragePurpose` 为首参；导入与业务绑定前使用 `get(purpose, id)` 校验用途。`forge.storage.download-url-ttl` 删除，下载时长改由用途声明。已有服务须新增 Flyway 迁移复制 SDK `upgrade-purpose.sql`（demo V14）：旧对象回填 `unspecified`、新登记显式提供用途；旧对象须按可信业务关联归类后才可按明确用途下载。

- 引入 `forge-sdk-ops` 的服务必须配置独立 `forge.ops.token`；平台须配置服务令牌映射，漏配启动失败。本仓库本地启动须显式提供 IAM、platform 与 demo-service 的三个运维令牌环境变量，详见接入指南。

- Maven 版本从 `0.1.0-SNAPSHOT` 切换至 `0.1.0`，使用旧坐标的工程需更新 BOM 版本。
- 本版尚未承诺与其他版本的 Shell/Sub-App 共享依赖兼容；两端应使用同一版本。
