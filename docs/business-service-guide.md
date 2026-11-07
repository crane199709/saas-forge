# Business Service 接入指南

需要 JDK 17、Maven 3.9+、可用 Docker（Testcontainers）以及平台发布的 Maven 仓库。在 Maven settings.xml 配置该仓库和凭证，所有 Forge 制品须使用同一发布版本。以下以 SDK 0.1.0 为例。

## 生成与验证

在新仓库目录执行：

```sh
mvn org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate -B \
  -DarchetypeGroupId=io.saasforge -DarchetypeArtifactId=forge-service-archetype \
  -DarchetypeVersion=0.1.0 -DgroupId=com.example -DartifactId=orders-service \
  -Dversion=1.0.0-SNAPSHOT -Dpackage=com.example.orders \
  -DserviceName=orders-service -DpathPrefix=orders
cd orders-service
mvn verify
```

`serviceName` 是注册名和 Manifest 上报身份；`pathPrefix` 不带 `/`，是网关前缀及示例 Sub-App 编码。根包用 `package`。业务工程版本独立于 SDK 版本；BOM 的 forge.version 锁定 SDK 发布版本，升级时调整父 POM 版本。

工程继承发布的 `forge-dependencies`，包含十一个 SDK starter、Boot 插件、共享架构规则，以及真实 PostgreSQL/Redis 容器测试。测试使用自身 classpath 的数据库初始化脚本与测试签名密钥，不要求克隆平台仓库，也不连接共享数据库。`verify` 包含 HTTP 租户隔离、功能权限、Data Policy 及 ArchUnit；MQ/Nacos/Manifest 远程连接在测试配置关闭，因此该验证不替代平台联调。

示例复用 demo-service 的订单 CRUD、分类 Provisioning、Outbox 通知和统一信封；不默认引入 storage。订单接口在服务内为 `/orders`，网关 StripPrefix 后外部为 `/orders/orders`。上线前把示例领域、全局权限码 `order:*`、Data Resource `order`、菜单、角色模板替换为本服务领域，避免与已有服务声明冲突。

维护模板时，在平台仓库执行 `cd backend && ./mvnw install -Parchetype-it`；该 profile 启用 archetype 自带的生成集成测试。普通 reactor `verify` 不运行这一独立 Maven 消费接缝，避免冷缓存下引用尚未安装的同次 SDK；已发布/安装 SDK 后也可单独 `./mvnw -pl forge-service-archetype clean verify -Parchetype-it`。CI 使用包含模板测试的 install 命令。

## 从配置到租户菜单

1. 与平台运维按[平台侧接入清单](service-onboarding-checklist.md)第 1 步准备目标数据库、schema、运行与迁移账号。业务开发者只维护 Flyway 迁移，不能用运行账号建表。租户表保留标准列、ENABLE + FORCE RLS、部分唯一索引与 `NOT deleted` 条件。
2. 按清单第 2 步登记服务专属 Manifest 令牌，第 3 步创建 `forge-tenant`、`forge-app`、`forge-audit` 及 `<serviceName>-order` Topic；platform 的 `forge.platform.provisioning.services` 加入服务注册名。
3. 提供下表配置后执行 `mvn spring-boot:run`。确认 Flyway 成功、Nacos 注册成功、IAM Manifest 上报成功。服务仅导入 `forge-common.yaml`；如使用独立服务配置，必须显式添加其 dataId，不能只在 Nacos 创建文件。
4. 按清单第 4—5 步追加网关路由和 `forge.gateway.services` 的 `prefix: /orders`、`realm: tenant`，重启网关。匿名访问业务接口得到 401，正确 Tenant Realm Token 按权限得到 200/403，OpenAPI 能访问且 servers 地址正确。
5. 按清单第 6 步发布对应 Sub-App、登记 App Registry（示例 app 编码为 pathPrefix）、向套餐开放应用和权限，再向成员角色授予权限及 Data Policy。重新登录租户 Shell，确认看到菜单、加载前端资源并成功调用服务。只有后端 Manifest 并不会自动生成可用的前端页面。

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
| FORGE_IAM_URI、FORGE_MANIFEST_TOKEN | IAM 内部地址及专属令牌 / 清单第 2 步 |
| FORGE_OPS_TOKEN | 服务专属运维令牌，与平台 `forge.platform.ops.tokens.<serviceName>` 一致 / 平台运维 |

环境如启用 Redis/Nacos/MQ 的认证，另按平台配置提供其凭证；本模板不内置任何共享环境凭证。`forge.manifest.version` 初始为 1，是清单版本，不是 Maven 版本。

## Manifest、Provisioning 与内部接口

权限码、名称、菜单、角色模板、Data Resource 或资源属性变化时递增 `forge.manifest.version`，与新清单一起发布；相同版本不能用于覆盖不同内容。JSON 在 `META-INF/forge/manifest.json`，权限注解与清单必须一致。

`OrderCategoryService` 实现 `TenantInitializer`，SDK 订阅 TenantCreated、恢复 CurrentTenant 并在初始化事务中调用它；业务层不自行处理租户 ID。初始化只补齐缺少的分类，部分唯一索引兜底并发，SDK 的消费去重与事务确保重投不会产生重复结果。失败时让异常传播以触发重试，不吞掉错误；已有初始化数据不得被重试覆盖。清理实现 `TenantDataCleaner`，保持逻辑删除。用平台测试租户确认初始化状态成功，纯 HTTP 测试不能证明真实 MQ 投递。

`forge-sdk-ops` 为 `/internal/ops` 及其子路径建立独立运维令牌认证，`forge.ops.token` 无默认值，漏配即启动失败；普通业务接口继续验证本服务 Realm 的 JWT。平台通过已上报 Manifest 的服务目录及 `lb://<serviceName>` 直连服务，Operator 须有 `platform:ops:read` 或 `platform:ops:manage` 才能使用对应功能。服务未引入能力、无实例、令牌未配或不一致时，仅标记该服务不可用。

本仓库本地启动 IAM、platform 与 demo-service 前，须分别设置独立随机值；平台和服务从同名环境变量读取对应令牌。以下变量只在当前终端生成，不写入版本库；其他终端启动时应使用同一组值：

```sh
export FORGE_IAM_OPS_TOKEN="$(openssl rand -hex 32)"
export FORGE_PLATFORM_OPS_TOKEN="$(openssl rand -hex 32)"
export FORGE_DEMO_OPS_TOKEN="$(openssl rand -hex 32)"
```

新业务服务在自己的部署中提供 `FORGE_OPS_TOKEN`，并在平台的 `forge.platform.ops.tokens.<serviceName>` 配置同一个值；不要复用 Manifest 上报令牌。E2E runner 会为隔离运行自动生成随机运维令牌。

内部 HTTP 接口必须放在 `/internal/` 下，网关拒绝该前缀；网络访问控制与服务自身认证仍由部署和实现保障。不要把内部接口改成其他路径绕过网关限制。

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

## 按需启用 storage 与镜像

需要附件时在 POM 添加同版本 `io.saasforge:forge-sdk-storage`，由平台提供 `forge.storage.endpoint`、`bucket`、`access-key`、`secret-key`；endpoint 必须供浏览器访问。参考 demo-service 的 attachment 功能及 V8 迁移，不应只添加依赖却遗漏配置和存储表。

需要 Business Number 时添加 `io.saasforge:forge-sdk-numbering`，把 SDK 中 `io/saasforge/sdk/numbering/schema.sql` 复制到 Flyway 迁移，在保存业务数据的事务中调用 `BusinessNumbers.next(rule)`。日期段按 Nacos 共享配置中的 `forge.business-zone` 计算，漏配时启动失败。参考 demo-service 的订单编号及 V9 迁移。

需要字典或参数时在 Resource Manifest 中声明 `dictionaries`/`parameters`（内容变化须升 `forge.manifest.version`），添加 `io.saasforge:forge-sdk-referencedata` 与 `spring-boot-starter-validation`，配置 `forge.reference-data.iam-uri` 与 `token`（同清单上报令牌，无默认值，漏配启动失败）。经 `ReferenceData` 读取当前租户的字典与按类型的参数，在请求体字段上用 `@DictionaryValue("<字典编码>")` 并在控制器参数加 `@Valid` 校验取值；引用其他服务的字典写作 `服务:编码`。结果按租户缓存，每次读取先比对 iam 在 Redis 发布的租户参考数据版本号（`ReferenceDataKeys`，同 ADR-0005 的做法），版本变化即重新拉取，另有 `forge.reference-data.cache-ttl`（默认 10 分钟）兜底。参考 demo-service 订单的客户来源及 V10 迁移。

需要导入导出时添加 `io.saasforge:forge-sdk-io`，复制 `io/saasforge/sdk/io/schema.sql` 到服务迁移，并提供 storage、reference-data 和 scheduling 的配置。模型必须是公开 `record`，每个字段使用 `@ExcelColumn`；字典字段同时用 `@DictionaryValue`。`ExcelIo.template` 写出模板，`importFile` 先校验全部行、再在同一事务内逐行调用写入器，`export` 的数据源应沿用列表查询条件逐页或游标读取。只支持 `.xlsx` 的首个工作表；日期使用 ISO 文本，不接受公式。导入源文件在任务结束后删除，错误报告与导出文件占用存储配额。

SDK 自动提供 `/io/tasks`、`/io/tasks/{id}` 和 `/io/tasks/{id}/download`，均只允许提交人访问。业务提交和模板接口须挂自己的权限码；前端的 `ImportDialog`、`ExportButton` 通过回调绑定同一服务的任务接口。参考 demo-service 的 `OrderIoService`、V12 迁移及 demo-sub-app 订单页。

导入错误报告保留当前租户的字典下拉，并提示填写名称而非编码。错误列加宽、文本自动换行并按内容估算行高。修正数据后可以直接重新上传报告，末尾“错误原因”列无需删除或清空，其旧说明不会参与校验；其他多余列仍视为格式错误。

`forge.io` 默认值：`workers=2`、`queue-capacity=8`、`import-limit=10000`、`export-limit=100000`、`heartbeat-interval=10s`、`heartbeat-timeout=2m`、`retention=7d`。异步执行保留提交时 JWT 授权身份与 ABAC 主体、策略、环境快照；标准列按后台系统写入记 NULL，发起人由 Audit Event 记录；无需在业务回调手工绑定。Redis 的 `forge:io:<服务名>:tenants` 集合保留提交过任务的租户 ID，供 Global Task 在进程重启后逐租户收尾，不绕过 RLS；停机或迁移时须保留这个键。已提交的导入不会因源文件清理失败而改报失败，清理失败由维护任务继续处理；SDK 不自动重试业务任务。

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
| Manifest 被拒 | 注册名与 IAM token 配置键是否一致、令牌和清单 version 是否正确 |
| MQ 启动失败 / 初始化 pending | 使用 Proxy gRPC 而非 nameserver 地址、Topic 是否创建、platform 是否登记服务 |
| 看不到菜单或加载失败 | Manifest、App Registry、套餐、角色、远程入口地址与 CORS；参照清单第 6 步 |

## 维护者：模板冒烟

准备 Java 17、Node 24、pnpm 12.4.2 与可用的 Docker daemon，在仓库根目录运行 `bash deploy/template-smoke/run.sh`。脚本发布当前版本到临时 Maven/npm 仓库，在仓库外生成两个工程，运行后端 verify 和前端安装、lint、类型检查、构建，再复用主链路 E2E 环境启动生成服务。新服务使用独立 smoke schema、Manifest 令牌及 /smoke 网关路由；浏览器以真实 IAM 登录验证菜单、成员授权与内部路径拒绝。数据库写入仅限本次创建的临时容器，退出后删除容器和卷。MQ 与 Nacos 在此 HTTP 接缝关闭，不覆盖真实消息投递。

完整输出保存在 `deploy/template-smoke/run.log`，服务日志保存在 `deploy/e2e/run-*`；CI 同时保留 Playwright 报告，独立工作流在 PR/main 运行并作为发布质量门。
