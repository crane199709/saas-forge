# Business Service 接入指南

需要 JDK 17、Maven 3.9+（仅用于执行 archetype 生成，生成的工程自带 Maven Wrapper）、可用 Docker（Testcontainers）以及平台发布的 Maven 仓库。在 Maven settings.xml 配置该仓库和凭证，所有 Forge 制品须使用同一发布版本。以下以 SDK 0.1.0 为例。

## 生成与验证

在新仓库目录执行：

```sh
mvn org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate -B \
  -DarchetypeGroupId=io.saasforge -DarchetypeArtifactId=forge-service-archetype \
  -DarchetypeVersion=0.1.0 -DgroupId=com.example -DartifactId=orders-service \
  -Dversion=1.0.0-SNAPSHOT -Dpackage=com.example.orders \
  -DserviceName=orders-service -DpathPrefix=orders
cd orders-service
./mvnw spotless:apply
./mvnw verify
```

模板按示例包名排版，`spotless:apply` 按实际包名重排 import 与折行；`verify` 最后做格式检查，不合规时失败。格式规则在父 POM `forge-dependencies` 中，生成工程只声明 Spotless 插件，并带 XML 规则文件 `spotless-xml.prefs`。

Java 的 `if`、`else`、`for`、`while`、`do-while` 语句体必须使用大括号，即使只有一条语句。父 POM 自动在 `validate` 阶段运行 Checkstyle `NeedBraces`，检查生产与测试代码，违规即失败；`./mvnw validate` 可快速检查，`test`、`verify`、`install` 也会先执行。规则内联在父 POM 中，生成工程无需复制 Checkstyle 配置；升级已有服务时更新父 POM 版本、补齐大括号，并重新复制规范文档和更新 `AGENTS.md`。

`serviceName` 是注册名和 Manifest 上报身份；`pathPrefix` 不带 `/`，是网关前缀及示例 Sub-App 编码。根包用 `package`。业务工程版本独立于 SDK 版本；BOM 的 forge.version 锁定 SDK 发布版本，升级时调整父 POM 版本。

工程继承发布的 `forge-dependencies`，包含十一个 SDK starter、Boot 插件、共享架构规则，以及真实 PostgreSQL/Redis 容器测试。测试使用自身 classpath 的数据库初始化脚本与测试签名密钥，不要求克隆平台仓库，也不连接共享数据库。`verify` 包含 HTTP 租户隔离、功能权限、Data Policy 及 ArchUnit；MQ/Nacos/Manifest 远程连接在测试配置关闭，因此该验证不替代平台联调。

生成的工程带 `AGENTS.md`（规则摘要与构建、测试命令）和 `docs/standards/business-service.md`（业务服务编码规范，头部注明平台版本），供开发者与 Agent 改代码前阅读；规范副本不在业务工程修改，平台升级时按 CHANGELOG 重新复制。`SchemaCommentTest` 还检查 POM 中每个 SDK starter 自带 `schema.sql` 的表都已迁移，新增 starter 时漏复制迁移会失败。

主类名由 `serviceName` 派生：去掉 `-service` 后缀转为 PascalCase 再加 `Application`，如 `orders-service` → `OrdersApplication`。工程自带与平台仓库同版本的 Maven Wrapper（`mvnw`、`mvnw.cmd`、`.mvn/wrapper/`），命令一律用 `./mvnw`。

示例复用 demo-service 的订单 CRUD、分类 Provisioning、Outbox 通知和统一信封；不默认引入 storage。订单接口在服务内为 `/orders`，网关 StripPrefix 后外部为 `/orders/orders`。上线前把示例领域、全局权限码 `order:*`、Data Resource `order`、菜单、角色模板替换为本服务领域，避免与已有服务声明冲突。迁移只有两份：`V1__forge_sdk.sql` 是 SDK 必需表（Outbox、消费去重、Dead Event），始终保留；`V2__example_orders.sql` 是示例业务表，删除订单示例时连同代码整体删除，再从 V2 起编写本服务的迁移。

维护模板时，在平台仓库执行 `cd backend && ./mvnw install -Parchetype-it`；该 profile 启用 archetype 自带的生成集成测试。普通 reactor `verify` 不运行这一独立 Maven 消费接缝，避免冷缓存下引用尚未安装的同次 SDK；已发布/安装 SDK 后也可单独 `./mvnw -pl forge-service-archetype clean verify -Parchetype-it`。CI 使用包含模板测试的 install 命令。

## 中间件依赖门禁

生成工程以 `forge-dependencies` 为父 POM，无须额外声明插件，执行 `./mvnw validate` 即检查直接依赖；`test`、`verify` 等后续阶段也会先运行门禁。只 import BOM 不会继承门禁。

Redis 保留模板的 `spring-boot-starter-data-redis`，配合 `forge-sdk-tenant` / `forge-sdk-security`；RocketMQ 使用 `forge-sdk-mq`，调度使用 `forge-sdk-scheduling`；Nacos 使用模板中的 Spring Cloud discovery/config starter。门禁只拦原生客户端的直接声明，不拦 starter 的传递依赖。直接加入 `org.springframework.data:spring-data-redis`、Redisson、Lettuce、Jedis、RocketMQ/Nacos 原生客户端或 XXL-JOB 时，构建在 `validate` 失败并列出依赖及替代 starter；optional 与 test scope 同样检查。

基础设施模块确需直引时，在模块 POM 显式设置 `forge.middleware.skip=true` 并注释原因；SDK MQ 的 RocketMQ 传输与 Reference Data 的 Redis 版本缓存是当前两处豁免。平台 gateway、iam、platform 及 tenant 等模块经受支持的 starter 接入，继续受门禁约束。新增或变更豁免时同步[SDK 依赖与中间件豁免清单](sdk-dependency-gate.md)。业务服务保持默认门禁。

## Sub-App 本地启动

前端脚手架按创建时的应用编码和端口生成 `pnpm start:<应用编码>`。例如应用编码为 `lowcode`、端口为 `5176` 时，在生成的工程目录执行：

```sh
pnpm start:lowcode
```

该命令执行 `pnpm build && pnpm preview --host 127.0.0.1 --port 5176`，构建成功后提供 `http://127.0.0.1:5176/remoteEntry.js`，供本机租户 Shell 加载；将这个入口登记到 App Registry。源码修改后重新执行。`pnpm dev` 用于独立开发宿主，支持 HMR。

通过平台仓库的 `pnpm create:sub-app` 生成时，在 `frontend` 下执行 `pnpm --filter @forge/lowcode-sub-app start:lowcode`。已生成工程不会自动补脚本，可按本工程编码和端口将上述命令加入 `package.json` 的 `scripts`。

## 从配置到租户菜单

1. 与平台运维按[平台侧接入清单](service-onboarding-checklist.md)第 1 步准备目标数据库、schema、运行与迁移账号。业务开发者只维护 Flyway 迁移，不能用运行账号建表。租户表保留标准列、ENABLE + FORCE RLS、部分唯一索引与 `NOT deleted` 条件。
2. 按清单第 2 步由 Operator 在控制台登记服务凭证，将仅显示一次的明文安全注入 `FORGE_MANIFEST_TOKEN`，无需重启 IAM；按第 3 步创建 `forge-tenant`、`forge-app`、`forge-audit`、`forge-notification` 及 `<serviceName>-order` Topic；platform 的 `forge.platform.provisioning.services` 加入服务注册名。
3. 提供下表配置后执行 `mvn spring-boot:run`。确认 Flyway 成功、Nacos 注册成功、IAM Manifest 上报成功。服务仅导入 `forge-common.yaml`；如使用独立服务配置，必须显式添加其 dataId，不能只在 Nacos 创建文件。
4. 按清单第 4—5 步追加网关路由和 `forge.gateway.services` 的 `prefix: /orders`、`realm: tenant`，重启网关。匿名访问业务接口得到 401，正确 Tenant Realm Token 按权限得到 200/403，OpenAPI 能访问且 servers 地址正确。
5. 本地通过租户 Shell 联调时，SDK `api()` / `page()` 自动将 `/orders/orders` 变为浏览器 `/api/orders/orders`，Shell 剥掉 `/api` 后转发网关。开发 Shell、生产 Shell 与独立宿主固定代理 `/api` 和 `/iam`，新增服务不改前端代理；已生成 Sub-App 删除 `apiPrefixes`。网关路由与源码业务路径保持原样，详见[接入清单第 4 节](service-onboarding-checklist.md#4-nacos-网关路由与前缀声明)。
6. 按清单第 6 步发布对应 Sub-App、登记 App Registry（示例 app 编码为 pathPrefix）、向套餐开放应用和权限，再向成员角色授予权限及 Data Policy。重新登录租户 Shell，确认看到菜单、加载前端资源并成功调用服务。只有后端 Manifest 并不会自动生成可用的前端页面。

## 必须配置项

生成的 application.yml 不为环境地址和密码设默认值；通过环境变量或等价外部配置提供：

| 环境变量 | 含义 / 提供方 |
|---|---|
| FORGE_SERVICE_PORT | 服务监听端口 / 部署运维 |
| FORGE_NACOS_ADDR | Nacos 地址 / 清单第 4 步 |
| FORGE_DB_URL | 完整 JDBC PostgreSQL URL / 清单第 1 步 |
| FORGE_DB_USER、FORGE_DB_PASSWORD | 运行账号和密码 / 清单第 1 步 |
| FORGE_DB_MIGRATOR_USER、FORGE_DB_MIGRATOR_PASSWORD | schema 所有者账号和密码 / 清单第 1 步 |
| FORGE_DB_SCHEMA | 数据库准备脚本使用的短名 / 清单第 1 步 |
| FORGE_REDIS_HOST、FORGE_REDIS_PORT | 平台共享 Redis / 平台运维 |
| FORGE_SECURITY_ISSUER | Tenant Realm 的公开 issuer，与 Token 一致 / 平台运维 |
| FORGE_SECURITY_JWK_SET_URI | IAM JWKS 的服务直连 URL / 平台运维 |
| FORGE_ROCKETMQ_ENDPOINTS | RocketMQ Proxy gRPC 地址 / 清单第 3 步 |
| FORGE_IAM_URI、FORGE_MANIFEST_TOKEN | IAM 内部地址及控制台登记后一次性交付的服务凭证 / 清单第 2 步 |
| FORGE_OPS_TOKEN | 服务专属运维令牌，与平台 `forge.platform.ops.tokens.<serviceName>` 一致 / 平台运维 |

环境如启用 Redis/Nacos/MQ 的认证，另按平台配置提供其凭证；本模板不内置任何共享环境凭证。`forge.manifest.version` 初始为 1，是清单版本，不是 Maven 版本。

### 服务凭证获取与轮换

凭证管理入口为平台控制台“服务凭证”（`/service-credentials`），仅持有 `platform:service-credential:manage` 的 Operator 可进入。点击“登记凭证”并填写服务名，复制一次性的 `FORGE_MANIFEST_TOKEN=...` 片段；关闭对话框或刷新后不能再次查看。尚未上报 Manifest 只提示，不阻止登记。IAM 不可用时该页标记不可用，其他平台功能仍可使用。

平台首次接入的引导：先启动 IAM，由持有独立 IAM Ops Token 的管理员直连 `POST /internal/ops/service-credentials`，请求体为 `{"service":"forge-platform","actor":"<已验证 Operator UUID>"}`，Authorization 使用 IAM 的 Ops Token。将响应的一次性 `data.token` 写入 Platform 私有环境文件的 `FORGE_MANIFEST_TOKEN` 后启动 Platform。Demo 等其他服务使用相同流程，或在控制台可用后登记；不要直接改权限表或关闭网关认证。

凭证轮换：为同一服务创建新凭证，更新服务配置并验证上报成功，再停用旧凭证。停用请求提交后旧凭证立即失效，多条有效凭证可以并存；登记、停用和轮换由 IAM 记录 Platform Realm 审计。不要依赖已弃用的 `forge.iam.manifest.tokens` 静态引导配置；应用已移除本地静态默认凭证，既有本地环境需自行登记数据库凭证或显式配置引导值，共享环境迁移单独执行。

### 服务接入诊断

控制台“服务接入诊断”（`/onboarding`）由 `platform:onboarding:read` 权限控制，只能刷新读取。配置层读取与网关相同 namespace/group 下的 Nacos `forge-gateway.yaml` 路由与认证前缀；读取前后客户端必须为 UP，否则显示未知。来源标为 `NACOS_CLIENT` 并带检查时间：客户端仍可能使用 snapshot/failover，不能据此证明远端最新配置或网关运行时已加载。

运行层经 `lb://<service>/internal/onboarding-status` 使用该服务独立 Ops Token 读取本实例上报状态；不透传 Operator JWT，也不使用 Service Credential。状态包含是否启用、已上报版本、最近成功/失败时间、失败分类和阶段，不输出令牌或 IAM 地址。未成功上报的服务仍可由 Nacos 路由或平台配置的 Ops Token 进入候选列表；IAM 目录失败只影响目录完整性，单服务不可达不掩盖其他服务。IAM 自身直接登记清单，未引入 reporter 时不提供此状态端点。负载均衡读取只代表选中的实例，不代表全副本一致。

请求层每次刷新向环境配置 `forge.platform.onboarding.gateway-uri`（本地 `FORGE_ONBOARDING_GATEWAY_URI`）指定的真实网关发出 `GET /<prefix>/v3/api-docs`。只有 HTTP 200、JSON OpenAPI 结构和 `x-forge-service` 服务身份都匹配才显示已验证；HTML 或其他服务的 200 不通过。SDK OpenAPI 自动加入身份扩展。无可读取的 Nacos 前缀时显示未探测；本层只证明该次网关接口文档路由，不证明业务授权或 Shell `/api` rewrite。四个并发探针、单次排队/执行最长十二秒，繁忙或超时显示未探测。

Manifest 上报在启动完成后异步执行，不等待 IAM 恢复：初次尝试失败后再退避重试三次（1、2、4 秒），连接/读取超时保持 5/10 秒；仍失败则每五分钟补报一次，最多十二次。成功即停止，应用关闭取消等待与进行中的请求；到达上限后保留失败状态，修正配置后重启服务重新上报。失败区分网络、凭证被拒、服务名不匹配、版本冲突、清单非法和其他响应；最近成功与最近失败各自保留。内部状态端点受 Ops Token 链保护，网关继续拒绝 `/internal/`。

## Manifest、Provisioning 与内部接口

权限码、名称、菜单、角色模板、Data Resource、资源属性或 Sensitive Field 声明（含注解）变化时递增 `forge.manifest.version`，与新清单一起发布；相同版本不能用于覆盖不同内容。JSON 在 `META-INF/forge/manifest.json`，权限注解与清单必须一致。

敏感字段可在同一 JSON 文件声明，与 permissions、dictionaries、dataResources 一起上报：

```json
{
  "sensitiveFields": [
    { "path": "customer.phone", "grade": "L3", "defaultRule": "phone" },
    { "path": "customer.name", "grade": "L2" }
  ]
}
```

也可只在 DTO 声明，构建期 `ManifestCli` 与启动期共用扫描器，无需实例化业务类：

```java
import io.saasforge.sdk.security.SensitiveField;

public record CustomerResponse(
        @SensitiveField(path = "customer.phone", grade = "L3", defaultRule = "phone") String phone) {}
```

同一路径只选一种声明来源；多 DTO 重复登记同一路径也会失败，可把登记集中到共享 DTO。record component 传播到字段与 accessor 的同一注解只计一次。路径每段遵循普通标识符规则（最多 63 字符），不支持数组索引；分级固定 L1–L4，默认规则可省略。路径、分级或默认规则变化时升清单版本，例如 `7` 改为 `8`，然后重新构建并发布；相同版本不能覆盖不同内容。无声明时不输出 `sensitiveFields`，升级 SDK 本身无需改清单版本。登记与实际脱敏执行分开，接口展示与导出仍需接入统一执行通道；完整约束见 [敏感字段登记](standards/business-service.md#敏感字段登记)。

启用统一脱敏执行通道的服务还需配置以下内容；空声明且未使用通道的旧服务无需添加：

```yaml
forge:
  field-masking:
    enabled: true
    iam-uri: ${FORGE_IAM_URI}
    token: ${FORGE_MANIFEST_TOKEN}
```

两项地址和凭证随环境注入，启用后漏配会启动失败。凭证与本服务清单上报相同，IAM 按其认证的服务身份限制字段范围。平台“数据安全”维护固定策略库与四级分级默认，租户“数据安全”只能选择字段规则；生效优先级为租户覆盖 > 字段默认 > 分级默认，L4 禁止导出。预览共用执行引擎，失败时不回显样例原文。配置与字段登记变更写审计，预览样例不入审计；引用规则不能删除。导出与在线策略按每次执行直接读取 IAM，修改从下次读取起生效；IAM 不可用时必须失败关闭。日志使用独立的一秒有效快照异步刷新，首次或过期可丢值，至多一秒收敛。

导出敏感列在 record component 上使用 `@SensitiveField` 对应登记路径，`ExcelIo.export` 自动在写单元格前执行规则；零行也检查规则和 L4 禁止导出。缺规则或执行失败的任务为 `FAILED`，不生成结果文件，并记录不含原文的失败审计。导入、模板与错误报告保留原行为。日志按 `log.info("customer.phone={}", phone)` 或 `SensitiveLogValue.of("customer.phone", phone)` 传参；SLF4J `addKeyValue` 经 `%kvp` 同样处理，包装对象的 `toString()` 永不输出原值。SDK 在 Boot 日志初始化前注册全部消息别名，不依赖 IAM 启动。支持范围与异步 MDC、失败占位语义见 [敏感字段导出与日志](standards/business-service.md#敏感字段导出与日志)。

在线通道使用 DTO 上的 `@SensitiveField` 与 IAM 登记共同决定脱敏，适用于嵌套对象和集合。SDK 在写出前完成整个 JSON 响应，缺字段、缺规则、null 下的缺规则或执行失败均为 500；一次响应复用一份快照。响应由实际选中的 JSON 转换器序列化到内存缓冲，保留原有视图、过滤器与声明类型；列表应分页，文件下载和流式输出走各自管线。业务代码不自定义在线脱敏 serializer 或手拼掩码；完整要求见 [在线敏感字段](standards/business-service.md#在线敏感字段)。

demo-service 的 `/masking/profiles` 返回手机号、身份证与普通说明，同一个 `MaskedProfile` 声明供在线与 `POST /masking/profiles/export` 的 Excel 导出复用（独立 `masking:export` 权限）；`POST /masking/profiles/log` 使用 `masking:read` 并通过 `SensitiveLogValue.of` 输出命名日志；`/masking/profiles/raw` 使用独立的 `masking:viewRaw` Permission Code 与不含原文的 `profile.viewRaw` 审计。原文 DTO 使用固定安全 `toString()`，普通查看即使持有原文权限仍返回脱敏值。demo 清单版本为 8，运行时复用 `forge.manifest.iam-uri` 和本服务凭证；隔离 E2E 的 IAM 地址覆盖也自动供该通道使用。

`OrderCategoryService` 实现 `TenantInitializer`，SDK 订阅 TenantCreated、恢复 CurrentTenant 并在初始化事务中调用它；业务层不自行处理租户 ID。初始化只补齐缺少的分类，部分唯一索引兜底并发，SDK 的消费去重与事务确保重投不会产生重复结果。失败时让异常传播以触发重试，不吞掉错误；已有初始化数据不得被重试覆盖。清理实现 `TenantDataCleaner`，保持逻辑删除。用平台测试租户确认初始化状态成功，纯 HTTP 测试不能证明真实 MQ 投递。

`forge-sdk-ops` 为 `/internal/ops` 及其子路径建立独立运维令牌认证，`forge.ops.token` 无默认值，漏配即启动失败；普通业务接口继续验证本服务 Realm 的 JWT。平台通过已上报 Manifest 的服务目录及 `lb://<serviceName>` 直连服务，Operator 须有 `platform:ops:read` 或 `platform:ops:manage` 才能使用对应功能。服务未引入能力、无实例、令牌未配或不一致时，仅标记该服务不可用。

所有服务端统一使用 `FORGE_OPS_TOKEN`；使用 SDK 上报清单的服务统一使用 `FORGE_MANIFEST_TOKEN`。IAM、Platform、Demo 与生成服务不再使用服务名前缀的变量名。两类凭证独立，各服务的值也独立，漏配时启动失败；环境文件必须按服务分开加载。

Platform 的全部 `forge.platform.ops.tokens` 统一配置在专属 `forge-platform.yaml` 中，按真实服务名映射到目标服务的 Ops Token，内置和外部服务使用同一入口。部署可使用受控 Nacos dataId `forge-platform.yaml`；本地复制 `deploy/config/forge-platform.example.yaml` 到私有 `deploy/data/forge-platform.yaml`，由 `.env.platform.local` 的 `SPRING_CONFIG_ADDITIONAL_LOCATION` 加载。新增服务只在这份映射中增加条目，不在应用 YAML 追加服务特例。

本地 IAM、Platform、Demo 分别加载 `.env.iam.local`、`.env.platform.local`、`.env.demo.local`；样例位于仓库根目录 `.env.<服务>.example`。迁移既有环境时保留当前令牌值，再切换变量名与加载路径，验证成功后移除旧共享文件和临时覆盖配置。E2E runner 使用同一结构，随机生成的隔离令牌不继承开发凭据。

内部 HTTP 接口必须放在 `/internal/` 下，网关拒绝该前缀；网络访问控制与服务自身认证仍由部署和实现保障。不要把内部接口改成其他路径绕过网关限制。

## 资源恢复

Restore 由每类资源自行实现。SDK 只提供 `io.saasforge.sdk.core.api.ResourceErrorCode`、共享文案和 `ListPage` 的可选已删除视图；不提供通用恢复 Mapper 或跨资源恢复服务，不新建集中回收站，也不提供彻底删除。Membership 和 Identity 不接入恢复，避免重新授予权限。

后端接入时沿用该资源现有管理权限与租户上下文：列表默认只查未删除记录，显式 `deleted=true` 时只查已删除记录；恢复接口使用 `POST /<资源>/{id}/restore`，成功返回 HTTP 200 的 `ApiResponse.ok()`。请求不接受租户 ID 作为隔离依据；当前租户外的 ID 不得通过查询或恢复暴露。

1. 已删除列表、恢复前读取与恢复 UPDATE 写在 Mapper XML。`BaseMapper` 的常规查询和更新带逻辑删除条件，不能用来读取或恢复已删除记录。`deleted = true` 仅用于这些专用语句；常规列表、详情、JOIN 和业务写入继续保留各自的 `NOT deleted`，不能全局关闭逻辑删除、租户拦截器或 RLS。
2. 在同一事务内先获取与删除相同的锁，再读取已删除记录、校验资源规则并恢复。相关的删除、移动或其他结构变更必须遵守同一锁约定，避免父资源校验通过后又被删除。恢复不应重置业务状态或重建已不存在的权限关系；依赖关系如何保留与恢复由资源自己定义。
3. 唯一性以现有 `WHERE NOT deleted` 的部分唯一索引为准，提前检查后仍由数据库兜底并发；只把对应唯一索引的冲突映射为 `RESTORE_CONFLICT`，不要把所有数据完整性错误都当成恢复冲突。角色只检查编码，部门和岗位允许同租户重名，不新增名称唯一约束。
4. 恢复 UPDATE 带目标 ID、当前租户隔离条件和 `deleted = true` 条件，并检查受影响行数；按原有 Data Policy 控制可操作范围。设置 `deleted = false, updated_at = CURRENT_TIMESTAMP, updated_by = #{actorId}`，保留 `created_*`。XML 不触发 SDK 自动填充，`actorId` 从已认证上下文的 `CurrentActorProvider` 取得，无认证上下文时为 NULL，不能从请求体接收。
5. 在对外入口记录 `@AuditLog(action = "<资源>.restore", ...)`，包含资源 ID 与成功或失败结果；删除时的 `updated_at`、`updated_by` 仅表示最后一次写入，恢复会刷新它们，详细历史由 Audit Event 回答。

可直接参考 demo-service 的 [OrderController](../backend/examples/demo-service/src/main/java/io/saasforge/demo/order/controller/OrderController.java)、[OrderService](../backend/examples/demo-service/src/main/java/io/saasforge/demo/order/service/OrderService.java) 与 [OrderMapper XML](../backend/examples/demo-service/src/main/resources/order/mapper/OrderMapper.xml)：`GET /orders?deleted=true` 使用 `order:delete`，返回相同分页信封和 `updatedAt`、`updatedBy`；普通列表继续使用 `order:read`。`POST /orders/{id}/restore` 使用 `order:delete`，成功返回 HTTP 200，重复恢复、当前租户外或 Data Policy 范围外的 ID 均返回 `order.notFound` / 404。

订单恢复在事务内以 `SELECT ... FOR UPDATE` 锁住已删除行，与删除的条件 UPDATE 共用数据库行锁；专用查询与恢复 UPDATE 都保留 SDK 租户和 Data Policy 拦截。恢复只修改 `deleted` 和 `updated_*`，保留 Business Number、原有归属与业务状态，不重开 Todo 或重发 Notification；只把现有 `orders_number_key` 部分唯一索引的 23505 冲突映射为 `resource.restoreConflict`。公开 HTTP 行为、跨租户、Data Policy、冲突、并发，以及出站审计和通知契约见 [OrderRestoreTest](../backend/examples/demo-service/src/test/java/io/saasforge/demo/OrderRestoreTest.java)。

两种共享错误都用 `new ForgeException(ResourceErrorCode.<枚举值>)` 抛出，不在服务重新声明同一 code；`userTip` 随 `Accept-Language` 查 SDK 文案（默认中文，支持英文）。

| SDK 枚举值 | Error Code / HTTP | 语义与用户处理 |
|---|---|---|
| `RESTORE_CONFLICT` | `resource.restoreConflict` / 409 | 名称或编码与实际唯一索引冲突，先改名或处理冲突记录后再恢复 |
| `RESTORE_BLOCKED` | `resource.restoreBlocked` / 409 | 恢复依赖的关联资源仍已删除，先恢复关联资源；例如 Department 的父部门 |

前端按权限决定是否传入 `deleted`；不传时 `ListPage` 的旧属性、插槽和分页行为保持兼容。组件不发送 HTTP 请求，也不替页面作授权或确认。

| ListPage 属性或事件 | 用法 |
|---|---|
| `deleted?: boolean` | 受控属性：不传则隐藏视图切换；`false` 为未删除，`true` 为已删除，并自动追加恢复列 |
| `deleted-change(boolean)` | 页面更新 `deleted`、将页码重置为 1，再按当前查询条件加载 |
| `restore(row)` | 交回完整条目；页面确认后调用自己的恢复接口，成功后刷新列表，失败展示 SDK 返回的 `userTip` |
| `loading?: boolean` | 加载或恢复请求期间传 `true`，禁用视图切换、恢复和分页 |

```vue
<ListPage
  :page="page"
  :deleted="deleted"
  :loading="loading"
  @deleted-change="changeDeletedView"
  @page-change="loadPage"
  @restore="confirmAndRestore"
>
  <!-- 搜索与普通操作由页面按 deleted 视图区分。 -->
  <el-table-column prop="title" label="标题" />
</ListPage>
```

交付前经后端公开 HTTP 接口验证默认查询排除已删除行、已删除列表和恢复成功、权限拒绝、跨租户不可见且不能恢复、现有唯一索引冲突、关联资源受阻、重复及并发恢复，以及恢复后的审计列与原有业务状态。并发测试应覆盖恢复与删除/移动竞争并验证最终公开可见结果；前端通过 Playwright 验证切换视图、确认恢复、成功刷新和失败提示。共享组件自身可通过公开 props/events 与 DOM 测试验证。

## 通知与待办

需要站内 Notification 或 Todo 时添加可选依赖 `io.saasforge:forge-sdk-notification`，版本由 `forge-dependencies` 管理。注入 `NotificationClient`，在修改业务状态的同一 `@Transactional` 方法中调用；事务外明确抛出 `IllegalStateException`，业务回滚时不产生事件。Tenant 取自 `CurrentTenant`，来源服务取自必填且非空的 `spring.application.name`（最长 255 字符），调用方不传租户或服务名。

```java
import io.saasforge.sdk.notification.NotificationClient;
import io.saasforge.sdk.notification.NotificationLink;
import io.saasforge.sdk.notification.NotificationTarget;

// 在「订单提交」业务事务中发送事实通知，并开启待办。
notifications.send(
        NotificationTarget.memberships(membershipIds),
        "订单已提交", "订单 42 已提交", new NotificationLink("orders", "/orders/42"));
notifications.openTodo(
        "order:42:approval", NotificationTarget.permission("order:approve"),
        "审核订单", "订单 42 等待审核", new NotificationLink("orders", "/orders/42"), submittedAt);

// 在之后「订单审核完成」的业务事务中关闭同一业务键。
notifications.closeTodo("order:42:approval", approvedAt);
```

`NotificationTarget` 在 Membership 列表与 Permission Code 中二选一，列表不可为空。Notification 的权限目标在 IAM 创建收件记录时展开为当时的持有者，跨 Tenant 的 Membership 被忽略；Todo 的权限目标在读取时判断，权限收回或超出 Plan 后立即隐藏。`send` 返回 Notification UUID，所有收件人共享该 ID。

标题、正文必须为非空纯文本，分别最多 200、10000 个 Unicode 字符；业务键与 Permission Code 最多 255 字符。`NotificationLink` 可为 null；提供链接时，`appCode` 沿用 App Registry 的 `[a-z][a-z0-9-]{0,63}`，`route` 为以单个 `/` 开头、最多 2048 字符的合法 URI 路由，可带查询参数和片段，拒绝外部 URL、`//` 和反斜杠。Todo 的目标 Sub-App 不在当前 Plan 时隐藏。

Todo 按 Tenant、服务、业务键维护一条状态；业务发生时间使用 `Instant`，重试沿用原值。Outbox 不保证顺序，SDK 保留调用方提供的时间；IAM 只采用更晚的状态，关闭先到也保留记录，旧开启不能重开，之后的新开启可以重开。

部署须创建普通消息 Topic `forge-notification`（`deploy/rocketmq/create-topics.sh` 已包含）。消息 Tag 分别为 `NotificationSent`、`TodoOpened`、`TodoClosed`，record 契约位于 `io.saasforge.sdk.notification`，IAM 通过现有 `forge-iam` 消费组处理。SDK 复用既有 Outbox、消费去重和重试，不新增表；已有服务已配置 MQ 表时无需新增迁移。上述示例只说明 SDK 发布入口，IAM 收件箱与 Todo 能力由平台提供。

## MQ 重试耗尽与指标

升级 `forge-sdk-mq` 时，须在服务自己的 Flyway 迁移中增加 SDK `io/saasforge/sdk/mq/schema.sql` 中的 `forge_dead_event` 建表语句；新生成工程已包含该表。与 Outbox 一样，死信表是服务级基础设施表，不启用 RLS；`tenant_id` 仅用于恢复上下文和展示，原始消息体只在服务内部保存。

`forge.mq.max-delivery-attempts` 默认为 16，必须为正整数。失败投递未达到上限时继续重试；达到上限时，SDK 在业务事务回滚后独立提交 Dead Event，再确认 MQ 消息。捕获写入失败则继续返回失败，JSON 解析失败同样捕获。没有对应 Handler 的事件仍跳过并告警。Outbox 发送失败继续无限退避重试。

消费组的 RocketMQ `retryMaxTimes` 须不小于 SDK 上限。RocketMQ 5.3.2 默认重试 16 次，Proxy 下发的总投递上限为 `retryMaxTimes + 1`（[官方实现](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/proxy/src/main/java/org/apache/rocketmq/proxy/grpc/v2/common/GrpcClientSettingsManager.java#L134)）。本地 `deploy/rocketmq/create-topics.sh` 显式配置并回读 `forge-iam`、`forge-platform`、`demo-service` 的消费组；新增服务时用 `CONSUMER_GROUPS` 加入服务消费组，或在目标环境执行 `mqadmin updateSubGroup -n <namesrv> -c <cluster> -g <consumer-group> -r <retry-count>` 并用 `getConsumerConfig` 验证。提高 SDK 上限时同时提高消费组重试次数；初始化脚本接受 `FORGE_MQ_MAX_DELIVERY_ATTEMPTS` 与 `RETRY_MAX_TIMES`，低于 SDK 上限即失败。Compose 分别从 `FORGE_MQ_MAX_DELIVERY_ATTEMPTS`、`FORGE_ROCKETMQ_RETRY_MAX_TIMES` 与 `FORGE_ROCKETMQ_CONSUMER_GROUPS` 传入这些值。

Micrometer 提供 `forge.mq.dead.events` Counter（标签 `consumer.group`，只计新捕获并成功提交的记录）与 `forge.mq.outbox.pending` Gauge（全部未发送事件，含退避中的事件）。关闭 `forge.mq.enabled` 后仍可观察 Outbox 积压；接入 Actuator 后通过服务现有指标出口采集。

## Dead Event 查询与处置

同时引入 `forge-sdk-mq`、`forge-sdk-ops` 和 `forge-sdk-web` 的 Servlet 服务会提供以下内部接口，全部由独立运维令牌保护；平台通过 `/ops/services/{service}/dead-events` 转发，网关外部路径为 `/platform/ops/services/{service}/dead-events`。

| 方法与内部路径 | 行为 |
| --- | --- |
| `GET /internal/ops/dead-events?status=PENDING&page=1&size=20` | 按状态分页，状态为 `PENDING`、`REPLAYED`、`ABANDONED`；默认待处置，页码从 1 开始，每页上限 100，按捕获时间倒序 |
| `GET /internal/ops/dead-events/{id}` | 单条元数据和最近错误 |
| `POST /internal/ops/dead-events/{id}/replay` | 请求体 `{ "reason": "修复后的重放原因" }`，在所属服务内重放 |
| `POST /internal/ops/dead-events/{id}/abandon` | 同样要求非空原因，将待处置事件标为已放弃 |

查询要求 `platform:ops:read`，两种处置要求 `platform:ops:manage`。响应遵循统一信封，成功处置返回 200 及元数据；分页放在 `meta.page`。元数据包括 ID、原事件 ID、消费组、主题、Tag、租户、尝试次数、错误、状态、捕获/处置时间和处置原因；不含原始消息体与原租户头。首次捕获和失败重放的错误摘要仅保留异常类型，不保存可能包含 payload 值的异常消息或根因消息；完整异常写入所属服务日志。平台命令的原因为空返回 400 `ops.reasonRequired`，事件不存在返回 404 `ops.deadEventNotFound`，已处置返回 409 `ops.deadEventAlreadyHandled`，重放失败返回 409 `ops.deadEventReplayFailed`；服务不可用返回 503 `ops.serviceUnavailable`。内部处置错误使用对应的 `deadEvent.*` 错误码。

重放保留原消费组、事件 ID 和原租户头，先恢复租户，再在同一事务内条件处置、登记去重并直接执行 Handler；不重新发布到 MQ。成功只生效一次，失败则回滚业务与处置标记，独立更新最近错误，仍为待处置。已下线 Handler、非法 JSON 或非法原租户头都不会被误标为成功。关闭 `forge.mq.enabled` 不影响查询和服务内重放。平台记录 `ops.deadEventReplay`、`ops.deadEventAbandon` 的成功或失败审计，包含 Operator、所属服务、死信 ID、处置原因及成功时的原事件 ID。

SDK 在启动后及每隔一天清理处置时间超过 90 天的已重放/已放弃事件，不按捕获时间清理待处置记录。该基础设施维护使用 SDK 自有线程和幂等条件删除，不新增 scheduling/Redis 运行依赖；多实例并发执行同样安全。引入 provisioning 的服务自动将死信接入 `TenantDataCleaner`，租户最终删除时只清理该租户的死信记录，保留其他租户和无租户事件。

## 定时任务与最近运行

需要调度时添加同版本 `io.saasforge:forge-sdk-scheduling`，在 Spring Bean 的无参 `void` 方法上声明 `@GlobalTask` 或 `@TenantTask`，并指定唯一 `name` 与 `cron`/`fixedDelay` 中的一项。服务必须配置 `spring.application.name`；cron 另须配置 `forge.business-zone`。Tenant Task 还须配置 `forge.scheduling.iam-uri` 与 `forge.scheduling.token`，可分别引用 `${forge.manifest.iam-uri}` 与 `${forge.manifest.token}`。每轮拉取试用/正常租户并逐个复查状态、绑定 `CurrentTenant`，单租户失败不会阻止后续租户。

多实例使用 `forge:task:<服务名>:<任务名>` 互斥，租期 30 秒、每 10 秒按持有者令牌续期；锁被占用或 Redis 不可用时跳过本次触发。业务任务须保持幂等。任务执行次数、耗时、跳过与租户成功/失败等原有 Micrometer 指标继续提供，标签仅含任务名。

SDK 自动将实际执行保存为 Task Run，不需要新增数据库表或迁移。注入 `TaskRunHistory` 后可通过 `current(name)` 查询当前运行（`Optional<TaskRun>`），通过 `recent(name)` 与 `problems(name)` 分别查询最近结束和最近非成功运行（`List<TaskRun>`，按结束顺序倒序）。`TaskRun` 包含任务类型、触发方式、运行状态与起止时间；Tenant Task 另有准确的租户成功/失败总数及最多 100 个失败租户，超出明细上限的租户请查服务日志。摘要只包含异常类型与 `ForgeException` 错误码，不包含异常 message 或参数；完整异常保留在日志。

运行记录沿用调度锁的全局 `StringRedisTemplate`，键前缀为 `forge:task-run:<服务名>:<任务名>:`。`current` 最多一条，`recent` 与 `problems` 各自最多 100 条；问题记录不会被后续成功挤掉。列表写入和裁剪原子完成，每次写入刷新已有记录键的 90 天 TTL。生产 Redis 的淘汰策略须保证带 TTL 的任务锁和运行记录不会被淘汰。这些记录只用于最近运行排查，Redis 数据丢失后不恢复历史，不作为长期审计存储，也不接入租户清理。

进程退出或失锁后，读取遗留 `current` 会显示 `INTERRUPTED`，下一次成功持锁执行会将其归档。旧运行结束不能清除接替者的 `current`。记录读写失败只记 WARN，不影响任务执行与释放锁；查询失败返回空结果，应结合 WARN 和服务日志排查。

同时引入 `forge-sdk-ops` 并配置既有服务运维令牌后，SDK 才提供任务内部 HTTP 接口；仅使用 scheduling 的服务不注册这些接口。平台继续使用 IAM 已上报服务目录、负载均衡和服务专属运维令牌，不透传 Operator JWT。

平台任务接口位于 `/ops/services/{service}/tasks`（经网关加 `/platform` 前缀）：

| 方法与路径 | 结果 |
|---|---|
| `GET /ops/services/{service}/tasks` | 已声明任务数组，包含 `name`、`type`、解析后的 `cron` 或 ISO-8601 `fixedDelay`、cron 的 IANA `zone`，以及 `latestRun`；优先当前运行，再取最近结束运行，无历史仍列任务 |
| `GET /ops/services/{service}/tasks/{name}/runs?view=recent` | 最近结束运行，`view=problems` 查询最近非成功运行；默认 `recent`，其他值返回 400 |
| `POST /ops/services/{service}/tasks/{name}/trigger` | 请求体 `{}` 触发整轮任务，`{"targetTenantId":"<UUID>"}` 重跑单个 Tenant；返回 `{"runId":"<UUID>"}`，执行体异步运行 |

以上结果均放在标准 `ApiResponse.data` 中。SDK 内部接口把相同路径前缀替换为 `/internal/ops/tasks`；内部触发体还须携带平台从可信上下文获取的 `operatorId`。查询要求 `platform:ops:read`，触发要求 `platform:ops:manage`；平台记录 `ops.taskTrigger` 或 `ops.taskTenantRetry`，包含 Operator、服务、任务、目标 Tenant、运行 ID 和失败错误码。失败租户直接使用每条 Task Run 的 `failedTenants` 与 `failureCount`，不另查业务数据。

手动与定时触发抢同一把锁，已持锁时同步返回 409 `task.alreadyRunning`。指定 Tenant 时先同步校验 trial/active，资格不符返回 409 `task.tenantNotEligible`；后台执行仍绑定并复查该 Tenant，不绑定发起人的 Membership/Operator 身份。Global Task 传入 Tenant 返回 400 `request.invalid`；未知任务返回 404 `task.notFound`。手动触发不改变任务原有幂等要求。

## 按需启用 storage 与镜像

需要附件时在 POM 添加同版本 `io.saasforge:forge-sdk-storage`，由平台提供 `forge.storage.endpoint`、`bucket`、`access-key`、`secret-key`；endpoint 必须供浏览器访问。把 SDK 的 `io/saasforge/sdk/storage/schema.sql` 复制为服务的 Flyway 迁移；脚手架默认不启用 storage，不应只添加依赖却遗漏配置和存储表。

每类文件在可信服务代码中声明 `StoragePurpose`，编码、扩展名、大小和下载时长均为 Java 常量，不由客户端、Tenant 或 Operator 选择。例如 demo 的订单附件：

```java
private static final StoragePurpose ATTACHMENT = new StoragePurpose(
        "order-attachment", Set.of("txt", "pdf", "xlsx", "png", "jpg", "jpeg"),
        20L * 1024 * 1024, Duration.ofMinutes(5));

var upload = storage.prepareUpload(ATTACHMENT, filename, contentType, size);
// 客户端凭 upload 直传后，确认存储并在登记业务关联之前校验用途。
storage.completeUpload(objectId);
var object = storage.get(ATTACHMENT, objectId);
var download = storage.prepareDownload(ATTACHMENT, objectId);
// 服务端写入同样显式传用途：storage.write(ATTACHMENT, filename, contentType, size, input)。
```

用途大小上限为 1 字节至 5 GiB，下载时长为 1 秒至 7 天的整秒；扩展名不含点、忽略大小写。SDK 在预留容量和写入之前拒绝不支持的扩展名或过大文件，错误码分别为 `storage.extensionNotAllowed`、`storage.fileTooLarge`；下载、导入和绑定的预期用途不一致时为 `storage.purposeMismatch`，跨租户仍为 `storage.objectNotFound`。业务权限仍须在使用对象前检查。前端沿用 `ApiError.userTip` 展示拒绝原因，申请失败后停止直传和业务确认。

已有服务升级时，把 `io/saasforge/sdk/storage/upgrade-purpose.sql` 复制为新的 Flyway 迁移（demo 为 V14），不要改已执行的建表迁移。脚本增加必填 `purpose` 列、把旧行回填为 `unspecified`，随后移除列默认值；新登记必须显式提供用途。未知用途旧对象仍保留，普通用途不能下载；只有可信业务关联能够明确归类时，才在独立迁移中更新编码，不能根据扩展名猜用途。全局 `forge.storage.download-url-ttl` 已删除，改用用途的 `downloadUrlTtl`。参考 demo-service 的 attachment 功能及 V8/V14 迁移。

需要 Business Number 时添加 `io.saasforge:forge-sdk-numbering`，把 SDK 中 `io/saasforge/sdk/numbering/schema.sql` 复制到 Flyway 迁移，在保存业务数据的事务中调用 `BusinessNumbers.next(rule)`。日期段按 Nacos 共享配置中的 `forge.business-zone` 计算，漏配时启动失败。参考 demo-service 的订单编号及 V9 迁移。

需要字典或参数时在 Resource Manifest 中声明 `dictionaries`/`parameters`（内容变化须升 `forge.manifest.version`），添加 `io.saasforge:forge-sdk-referencedata`（脚手架已带 `spring-boot-starter-validation`），配置 `forge.reference-data.iam-uri` 与 `token`（同清单上报令牌，无默认值，漏配启动失败）。经 `ReferenceData` 读取当前租户的字典与按类型的参数，在请求体字段上用 `@DictionaryValue("<字典编码>")` 并在控制器参数加 `@Valid` 校验取值；引用其他服务的字典写作 `服务:编码`。结果按租户缓存，每次读取先比对 iam 在 Redis 发布的租户参考数据版本号（`ReferenceDataKeys`，同 ADR-0005 的做法），版本变化即重新拉取，另有 `forge.reference-data.cache-ttl`（默认 10 分钟）兜底。参考 demo-service 订单的客户来源及 V10 迁移。

需要导入导出时添加 `io.saasforge:forge-sdk-io`，复制 `io/saasforge/sdk/io/schema.sql` 到服务迁移，并提供 storage、reference-data 和 scheduling 的配置。模型必须是公开 `record`，每个字段使用 `@ExcelColumn`；字典字段同时用 `@DictionaryValue`。`ExcelIo.template` 写出模板，`importFile(model, source, validate)` 异步校验全部行，成功后进入 `AWAITING_CONFIRM`，`rowCount` 是将写入的行数，此时没有业务写入。`export` 的数据源应沿用列表查询条件逐页或游标读取。只支持 `.xlsx` 的首个工作表；日期使用 ISO 文本，不接受公式。待确认期间保留源文件，完成、失败或过期后删除；错误报告与导出文件占用存储配额。

覆盖导入额外提供 `ImportWriter<T>`，沿用 `confirmImport(taskId, model, validate, writer)`：`beforeWrite(row)` 在确认事务中锁定旧记录，返回 `new ImportSnapshot(recordId, values)`，`values` 只放实际覆盖的旧字段，原值为 null 也必须保留；新增行返回 null。`write(row)` 执行业务写入，覆盖行返回从数据库重读的 `updated_at` 或版本列文本。SDK 保存旧值后才调用写入器，快照、业务写入与任务快照元数据共同提交，异常共同回滚；同一任务多次覆盖同一主键时保留最初旧值与最后的写后修改标记。`ImportBackups.list(taskId, size, offset)`（size 1–1000）提供当前租户的服务内分页读取；快照原文不经通用任务 HTTP 接口暴露。

已有 IO 服务升级须新增 Flyway 迁移复制 `io/saasforge/sdk/io/upgrade-import-backup.sql`（demo V15），包含快照表、RLS、标准列及任务的快照元数据与导入模型标识；新服务直接复制当前 `schema.sql`。SDK 自动保存模型全限定类名为 `IoTask.importModel`，确认时同步拒绝不匹配的模型，保留待确认状态。历史无模型标识的任务沿用原确认接口；模型名用于区分不同业务对象的覆盖记录，快照保留期内须保持稳定。

Demo 原追加导入继续使用三列模板及 `/orders/import`、`/orders/import/{taskId}/confirm`。覆盖导入使用 `/orders/import/overwrite/template` 的四列模板：标题、金额、客户来源、可选订单ID；空 ID 新增，有 ID 覆盖当前租户与数据权限内的已有订单，客户来源为空时写入 null。只覆盖标题、金额与客户来源，保留编号、状态、归属等其他字段。提交与确认路径分别为 `/orders/import/overwrite`、`/orders/import/overwrite/{taskId}/confirm`，上传仍复用 `/orders/import/upload`。历史界面按持久化的 `importModel === "io.saasforge.demo.order.model.OrderOverwriteRow"` 选择覆盖确认路由，其他及历史 null 值任务选择原路由；无需依赖页面内存。

任务返回 `snapshotCount`、实际截止时间 `snapshotExpiresAt`、`snapshotAvailable`（成功且有未过期快照）；追加与纯新增任务没有可用快照。`forge.io.backup-retention` 默认 90 天，服务可调整；租户清理自动逻辑删除本租户快照，其他租户不受影响。回滚只读预览调用 `ImportRollbacks.preview(taskId, model, reader)`，返回 `ImportRollbackPreview`：`available`、`unavailableReason`、`blocked`、`totalCount`、`rollbackableCount`、`conflictCount`、`snapshotExpiresAt` 与 `conflicts(recordId, reason)`。人工修改为 `RECORD_MODIFIED`，同模型同记录后续实际覆盖为 `LATER_IMPORT`，当前数据权限不可见或记录已删除统一为 `RECORD_UNAVAILABLE`；报告没有旧字段值。过期为 `available=false / SNAPSHOT_EXPIRED`，无覆盖快照为 `NO_SNAPSHOTS`，不返回“空报告即安全”的假象。

Demo 的 `GET /orders/import/{taskId}/rollback-preview` 使用独立 `order:importRollback`，清单版本升为 9；与导入权限分离，仍只允许查看本人任务。服务的 `ImportVersionReader` 按当前 Data Policy 读取数据库版本；其他租户或提交人任务仍为 404，只有导入权限仍为 403。已有 IO 服务新增迁移复制 `upgrade-import-rollback-preview.sql`（demo V16），增加后续实际覆盖标记，保持已有迁移不变。覆盖标记在业务行锁与确认事务内更新，只影响同租户、同模型、同记录的已存在快照，不依赖任务创建顺序。执行方必须在锁定当前可见业务行后调用 `ImportRollbacks.check(backup, reader)` 重查，并在同一事务中恢复，不能依赖先前的预览结果。执行入口如下；界面由导入历史与回滚对话框接入。

IO 统一声明 `IoStoragePurposes.SOURCE`（`io-source`，仅 xlsx、20 MiB）和 `RESULT`（`io-result`，仅 xlsx、100 MiB），下载时长均为 5 分钟。上传入口复用 SOURCE；`ExcelIo.importFile` 在提交任务之前核对用途，导出和错误报告写入 RESULT，`IoTasks.download` 按 RESULT 签发。不要把附件或结果对象当作导入源文件，否则会被同步拒绝，既不创建任务也不删除原对象。

SDK 自动提供 `/io/tasks`、`/io/tasks/{id}` 和 `/io/tasks/{id}/download`，均只允许提交人访问。业务提交和模板接口须挂自己的权限码；前端的 `ImportDialog`、`ExportButton` 通过回调绑定同一服务的任务接口。参考 demo-service 的 `OrderIoService`、V12 迁移及 demo-sub-app 订单页。

前端 `IoTaskApi.list(page)` 接收从 1 开始的页码并返回 SDK `Page<IoTask>`。例如 ``list: (page) => client.page<IoTask>(`/demo/io/tasks?page=${page}&size=10`)``；升级时须将旧的无参列表回调改为转发页码，并移除取 `.items` 的操作。`ImportDialog` 与 `ExportButton` 使用分页元数据提供翻页入口，只有当前页参与刷新，较早待确认任务在重开弹窗或刷新页面后仍可翻页找到。


回滚执行使用 `ImportRollbacks.execute(taskId, model, skipConflicts, ImportRollbackWriter)`。写入器的 `readVersion` 供预检，`lockAndReadVersion` 在 SDK 当前逐记录事务中锁住当前数据权限可见的业务行并返回实际版本，`restore` 只恢复 `backup.values()` 的业务字段并维护标准审计列。SDK 再锁快照并重新 `check`，恢复与 `RESTORED` 标记共同提交；失败记录可重试，已恢复记录不再读取业务版本或写入。数据库任务行锁覆盖整次调用，自动随事务/连接释放；执行中断后可直接重试，无持久占用状态。预检有冲突且未确认跳过时零恢复；持锁后发现新冲突且未确认跳过时停止后续恢复，已提交记录保留。明确传入 `skipConflicts=true` 才跳过冲突并继续。

Demo 提供 `POST /orders/import/{taskId}/rollback`，请求 `{"skipConflicts": false}`，要求独立 `order:importRollback`，仍限当前 Tenant 的本人任务。成功或部分完成返回 200 的 `ImportRollbackResult(taskId, status, unavailableReason, restoredCount, skippedCount, failedCount, conflicts)`，状态为 `SUCCEEDED` 或 `PARTIAL`；默认冲突返回 409 `io.rollbackConflicts`，过期、无快照或模型不支持等返回 409 `io.rollbackUnavailable`，精确原因与冲突清单从 GET 预览重新获取。SDK 执行接口对这两类结果仍返回 `BLOCKED` / `UNAVAILABLE` 报告，供服务选择 HTTP 映射。任务 `rollbackStatus`、`rollbackAttempts`、`restoredCount`、`skippedCount`、`failedCount` 与原导入状态分开；次数只统计完成的调用，响应与任务计数为累计已恢复数及当前跳过/失败数，审计 `io.importRollback` 按逐记录事务提交写实际恢复/跳过/失败三项计数，整轮拒绝或无操作另记零计数事件，不再叠加整轮实际计数。因此执行中断后已提交恢复仍可追溯，重试跳过已恢复记录，幂等重复调用恢复数为 0。审计沿用 `AuditRecorder` 的提交后独立事务契约，写入失败仅记录日志；业务提交与审计写入之间发生进程终止仍可能丢事件。

已有服务另加 Flyway 迁移复制 `upgrade-import-rollback-execution.sql`（demo V17），保留 V15/V16。新服务当前 `schema.sql` 已含所需列。`IoMaintenance.expire` 在按租户维护中先与回滚任务锁互斥，再清空过期快照旧值并逻辑删除；任务上的总数、截止时间、已完成回滚结果与统计仍保留，过期明确拒绝新恢复。默认 90 天通过 `forge.io.backup-retention` 调整，租户删除流程继续清理该租户全部快照。

到期快照清理同时接入现有全局维护的已提交租户扫描，因为普通 Tenant Task 仅运行试用/正常租户，不能保证停用租户的保留期。全局路径只在既有基础设施维护豁免内清理快照，不扩大业务写入权限；任务锁内先汇总已提交的逐记录结果，再清空旧值，进程中断未完成汇总的成功记录也保留历史计数。租户删除同样立即清空快照字段旧值并逻辑删除。

Business Service 须提供带导入权限码的确认接口，调用 `ExcelIo.confirmImport(taskId, model, validate, writer)`，并返回排队后的 `IoTask`。每次确认重新传入相同模型、只读业务校验器与事务内写入器；SDK 从持久源文件完整重新校验，全部行通过才在同一事务内逐行写入。校验失败时生成新的 Error Report，一行不写。确认使用当次请求的认证与 ABAC 快照，可交给另一实例，也支持提交实例重启后的确认。初次提交使用三参 `importFile(model, source, validate)`，写入器仅在确认时提供；旧四参签名已删除。

只有同租户的提交人可以确认：其他成员返回 403 `io.confirmForbidden`，其他租户返回 404 `io.notFound`，重复确认、其他状态或已超时返回 409 `io.taskNotAwaitingConfirmation`。数据库条件更新只允许一个确认请求抢占成功；执行队列已满时返回 429 `io.busy`，任务保持待确认，可稍后重试。待确认任务不占执行容量，也不按心跳超时判中断；其 `heartbeatAt` 固定为进入待确认的时间，`finishedAt` 为空。超过 24 小时未确认，Global Task `forge-io-interrupted` 将其标记为 `FAILED` / `io.confirmationExpired` 并清理源文件，包含已停用租户。该状态使用现有 `varchar(16)`，无需新增表、列或迁移。

导入错误报告保留当前租户的字典下拉，并提示填写名称而非编码。错误列加宽、文本自动换行并按内容估算行高。修正数据后可以直接重新上传报告，末尾“错误原因”列无需删除或清空，其旧说明不会参与校验；其他多余列仍视为格式错误。

`forge.io` 默认值：`workers=2`、`queue-capacity=8`、`import-limit=10000`、`export-limit=100000`、`heartbeat-interval=10s`、`heartbeat-timeout=2m`、`retention=7d`、`backup-retention=90d`。异步执行保留提交时 JWT 授权身份与 ABAC 主体、策略、环境快照；标准列按后台系统写入记 NULL，发起人由 Audit Event 记录；无需在业务回调手工绑定。Redis 的 `forge:io:<服务名>:tenants` 集合保留提交过任务的租户 ID，供 Global Task 在进程重启后逐租户收尾，不绕过 RLS；停机或迁移时须保留这个键。已提交的导入不会因源文件清理失败而改报失败，清理失败由维护任务继续处理；SDK 不自动重试业务任务。

镜像统一采用 Spring Boot build-image：生成工程中运行 `mvn spring-boot:build-image -DskipTests`，默认镜像标签为 artifactId:version；用 `-Dspring-boot.build-image.imageName=命名空间/服务:版本` 覆盖。需要 Docker，首次下载 buildpack builder/run image。生成配置只引用必填环境变量，不内置地址或密钥；运行时注入上表变量。平台与 Shell 镜像参见 [镜像说明](../deploy/images/README.md)。

## 常见失败

| 现象 | 检查 |
|---|---|
| 无法解析 Forge 依赖 | 仓库 settings、凭证及 SDK 统一版本；不要用业务工程版本替换 SDK 版本 |
| 未解析的占位符、配置绑定失败 | 上表必须项是否注入；避免在测试之外使用测试固定值 |
| Flyway schema 不存在或 permission denied | 准备脚本是否对正确数据库执行、迁移账号是否拥有 schema、运行账号是否误作迁移账号 |
| ltree 类型不存在 | DBA 是否在 extensions 安装扩展、账号 search_path 是否按准备脚本设置 |
| JWT 401 | issuer 是否与实际 Tenant Realm Token 完全一致，JWKS 地址是否可达 |
| 已认证却 403 / 列表为空 | 套餐、成员角色权限及 Data Policy；无 Data Policy 默认拒绝 |
| Manifest 被拒 | 注册名与凭证绑定服务是否一致、凭证是否有效、清单 version 是否正确；503 表示 IAM 凭证库不可用 |
| MQ 启动失败 / 初始化 pending | 使用 Proxy gRPC 而非 nameserver 地址、Topic 是否创建、platform 是否登记服务 |
| 看不到菜单或加载失败 | Manifest、App Registry、套餐、角色、远程入口地址与 CORS；参照清单第 6 步 |

## 维护者：模板冒烟

准备 Java 17、Node 24、pnpm 12.4.2 与可用的 Docker daemon，在仓库根目录运行 `bash deploy/template-smoke/run.sh`。脚本发布当前版本到临时 Maven/npm 仓库，在仓库外生成两个工程，运行后端 verify 和前端安装、lint、类型检查、构建，再复用主链路 E2E 环境启动生成服务。新服务使用独立 smoke schema、Manifest 令牌及 /smoke 网关路由；浏览器以真实 IAM 登录验证菜单、成员授权与内部路径拒绝。数据库写入仅限本次创建的临时容器，退出后删除容器和卷。MQ 与 Nacos 在此 HTTP 接缝关闭，不覆盖真实消息投递。

完整输出保存在 `deploy/template-smoke/run.log`，服务日志保存在 `deploy/e2e/run-*`；CI 同时保留 Playwright 报告，独立工作流在 PR/main 运行并作为发布质量门。
