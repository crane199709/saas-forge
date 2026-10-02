# Project Example

本模块实现 #212 的 Project 创建与详情，以及 #213 的列表与修改、#214 的 Task 创建/分页/详情、#215 的 Task 修改/状态流转/删除、#216 的 Project 删除与父子竞争保护，通过 Starter 的 `TenantContextAccessor` 获取可信租户身份。Project 属于业务应用；不会访问底座数据库。本轮增加契约生成的 Gateway 路由、结构化日志、实际 Span / OTLP 导出与六类成功事实审计投递，决策见 [ADR 0054](../../docs/adr/0054-example-http-contracts-compose-into-route-catalog.md)。Permission、Feature、Quota 和浏览器闭环仍未交付。

## HTTP 契约

正式契约：[openapi.yaml](openapi.yaml)。入口为 `POST /api/v1/projects`、`GET /api/v1/projects`、`GET /api/v1/projects/{projectId}` 和 `PUT/DELETE /api/v1/projects/{projectId}`；Task 入口为 `POST/GET /api/v1/projects/{projectId}/tasks` 和 `GET/PUT/DELETE /api/v1/projects/{projectId}/tasks/{taskId}`。只接受 Tenant User Access Token。

- 名称必填且非空白，最多 200 个 Unicode 码点；描述可省略或为 null，最多 2000 个码点。名称允许重复；名称和描述不能含 NUL 字符。空白不裁剪；省略描述与 null 视为相同请求。
- 创建返回 `201`、资源和规范路径 `Location`；读取返回 `200` 和当前 `version`。ID 为规范小写 UUIDv7，时间为 UTC RFC3339 三位毫秒。系统字段不可写，也不接受 Tenant 的请求输入。
- 不存在和其他 Tenant 的资源均返回 `404 / PROJECT_NOT_FOUND`。失败采用带 `traceId` 的 Problem Details，不回显 Token、输入或 SQL。
- Task 标题与 Project 名称使用相同的 200 码点必填边界，描述最多 2000 码点且可选；标题允许重复。创建只接受 `title`/`description`，系统固定 `status=TODO`，返回 `projectId`、UUIDv7、版本 1、时间和嵌套路径 Location。不存在/其他 Tenant 父资源为 `404 / PROJECT_NOT_FOUND`；存在父资源下不存在或父子不匹配的 Task 为 `404 / TASK_NOT_FOUND`。
- Task 集合以 ID 升序游标分页，`limit` 默认 50、最大 100。首页省略 `cursor`，末页 `nextCursor=null`、`hasMore=false`。游标绑定 Tenant、父 Project 和固定排序，24 小时过期；非法/过期/跨范围游标、未知或重复查询参数、非法 limit 返回 `400 / VALIDATION_FAILED`。不提供筛选或自定义排序。
- 修改与删除使用单个强版本 `If-Match: "<version>"`；缺失为 `428 / VERSION_REQUIRED`，过期为 `409 / RESOURCE_VERSION_CONFLICT`。Project 修改用 PUT 替换名称和描述；省略/null 描述均清空。版本比较与更新原子完成，成功增加一次版本并维护更新时间。失败不改变数据；重新读取后用新键和最新版本恢复修改。Gateway 仅在 Project 路径及子路径允许 Console Origin 的 If-Match 预检，其他路径规则不变；真实浏览器行为仍随阶段 3 验收。
- Task PUT 必填 `title` 和 `status`，替换标题、描述和状态；省略/null 描述均清空。三种状态可任意切换或保持，包括重新打开。Task DELETE 原子比较版本并永久删除，返回 `204` 无响应体。同键重试在资源检查前重放原结果；新键访问已删除 Task 为 `404 / TASK_NOT_FOUND`。父 Project 不可见仍为 `PROJECT_NOT_FOUND`，可见父资源下伪造子 ID 为 `TASK_NOT_FOUND`。

- Project DELETE 永久删除空 Project，返回 `204` 无响应体；任意状态 Task（包括 DONE）均使删除返回 `409 / PROJECT_NOT_EMPTY`。版本过期优先返回 `RESOURCE_VERSION_CONFLICT`。必须通过 Task DELETE 清空子资源后，再以最新版本和新键删除 Project。稳定拒绝按键重放，不因后续清空或修改而改写原结果；成功删除后同键重试仍为 204，新键访问为 `PROJECT_NOT_FOUND`。
- Task 创建事务先锁父资源 `FOR KEY SHARE`；Project 删除先锁父资源 `FOR UPDATE`，再在新语句快照中检查版本与 Task。创建先提交则删除拒绝；删除先提交则创建稳定返回 404。复合外键 RESTRICT 为数据库最终防线，不进行软删除或级联删除。

- 列表按 UUIDv7 `id ASC` 排序，默认 50、最大 100；返回 `items/nextCursor/hasMore`，末页游标为 null，`hasMore=false`。游标绑定集合、Tenant、页大小和可见的末条 ID，继续翻页必须沿用原 limit；未知/重复查询参数、非法或不匹配游标返回 `400 / VALIDATION_FAILED`。静态数据无重复遗漏，不提供跨页事务快照。

## 原生启动

使用 JDK 17，在 IDE 直接运行 `io.saas.forge.example.ProjectApplication`。应用无需完整 Compose、打包 JAR、托管脚本或进程替换。

一次性准备与应用启停分离：

1. 在独立 PostgreSQL 18 实例或逻辑数据库边界，以管理员执行 [deploy/bootstrap.sql](deploy/bootstrap.sql)，创建 `project_db`、`project_migrator` 与 `project_app`。脚本只用于新边界，不能对已有数据库盲目重复执行。通过受限凭据渠道给两个登录角色配置各自秘密；SQL 不包含密码。
2. 使用独立迁移进程和 `project_migrator` 执行 Flyway，迁移位置为本模块 `src/main/resources/db/migration`。迁移进程独占迁移凭据；应用没有 Flyway 运行依赖，不读取迁移凭据。迁移账号的维护策略遵循 ADR 0017/0022。
3. 准备 Redis 中对应环境的 IAM 撤销索引和通过 Nacos 服务发现可用的 IAM JWKS 服务。依赖地址由开发者自行配置，可以使用已有基础设施，不要求本机容器。撤销索引未就绪时 Starter 会失败关闭，不得在真实环境伪造 ready 标记。
4. 在 Git 忽略的本地配置文件或 IDE 的外部配置中设置数据库 URL/`project_app` 凭据、Redis 地址及凭据、`security.jwt.issuer`、`saas.forge.environment`、Nacos discovery 地址/namespace/身份，以及本机监听端口。非敏感运行配置可按本地开发例外从外部文件加载。凭据仍使用环境变量、Secret 或受限文件，不提交模板。需要联调时使用 Nacos 发现 IAM，不能静态配置 IAM 下游地址。
5. 直接 Run/Debug 启动类。测试中用 Simple Discovery 定位受控 JWKS 设施，仅属于测试夹具，不能照搬为运行时服务发现配置。

当前 HTTP API 用于服务接入和后端验收；内部监听端口不是浏览器入口。浏览器仍须遵守既有受信 HTTPS、受控域名和 Gateway 拓扑，本轮后端接入不改写 #212–#216 的原验收范围。

## 数据与幂等边界

`projects`、`tasks` 与 `project_write_results` 均含非空 `tenant_id`，强制 ENABLE/FORCE RLS，读写分别由 USING/WITH CHECK 限制。`project_app` 非 owner、无 BYPASSRLS/继承/迁移角色成员资格，只有所需 DML 权限，V5 仅追加 name/description/version/updated_at 的列级 UPDATE 权限。业务的读和写均在同一事务连接上调用事务级 `set_config('app.tenant_id', ..., true)`；提交、回滚或断连清理上下文。

Task 通过 `(tenant_id, project_id)` 复合外键引用 Project，拒绝跨 Tenant 引用及删除含 Task 的 Project，不使用级联删除。V7 前向迁移为 Task 增加 title/description/status/version/updated_at 列级 UPDATE 和 DELETE 权限；运行账号仍不能更改 Tenant、Project、ID 或创建时间。V8 仅新增 Project DELETE 权限；V1–V7 保持不变。

写操作使用 `(identity_id, idempotency_key)` 唯一键，共用 Example 写操作键空间，不能把 Tenant 加入唯一键来放宽唯一性。事务级 advisory lock 使处理中重试立即返回 `409 / IDEMPOTENCY_REQUEST_IN_PROGRESS` 与 `Retry-After: 1`。SHA-256 指纹包含方法、规范路径和规范化请求体；修改和删除还包含 If-Match 版本。跨 Tenant 的键冲突不会读取旧 Tenant 的正文。

完成响应和业务变更同事务提交。完成后 24 小时内重放原始状态、正文和 Location；格式/字段 `400` 不占键，未提交的基础设施失败回滚并释放键。Task 创建的不可见父资源 404 是真实稳定业务失败，记录并重放原 Problem 正文。稳定业务 `4xx` 使用同样的完成结果格式；创建操作在字段校验通过后没有自然业务拒绝（不限制同名、Quota 或成员权限），创建测试用受控完成记录验证既存稳定失败重放；修改测试通过真实版本冲突和不可见资源验证稳定失败重放。

同 Tenant 过期键在请求时清理。跨 Tenant 过期键因 RLS 仍不可见，在受控维护清理前继续返回键冲突；从不为重用键绕过 RLS。使用迁移账号定期执行 [deploy/expire-write-results.sql](deploy/expire-write-results.sql)，删除完成超过 24 小时的记录。维护频率决定跨 Tenant 过期键的最长额外占用时间；记录不应永久保留。当前键空间属于这个独立业务服务，不声称与其他独立服务数据库进行分布式幂等仲裁。

## 验证

在仓库根目录运行：

```sh
./mvnw -pl examples/project-service -am verify
```

使用 Docker 中的隔离 PostgreSQL 18/Redis 容器、随机端口和真实 HTTP 服务。公开 HTTP 测试通过受控 JWKS 服务提供的公钥验证签名 Token，并执行 Starter 的正式认证、声明和 Redis 撤销检查；数据库使用正式 bootstrap、Flyway 迁移和受限运行账号。补充数据库权限测试证明 RLS 独立于业务 WHERE 条件成立。

模块已加入根 Reactor，因此既有后端 CI 的根 `verify` 包含 Example。验收结果与限制见 [#212 验收记录](../../docs/acceptance/issue-212-acceptance.md) 、[#213 验收记录](../../docs/acceptance/issue-213-acceptance.md) 、[#214 验收记录](../../docs/acceptance/issue-214-acceptance.md) 、[#215 验收记录](../../docs/acceptance/issue-215-acceptance.md) 和 [#216 与父 PRD 覆盖记录](../../docs/acceptance/issue-216-acceptance.md)。

## Gateway、日志、Trace 与成功事实

业务契约通过 `saas-forge-contracts/services/business-http-contracts.json` 登记，与平台契约一起生成 Route Catalog。Gateway 和 Example Starter 都使用生成目录，且各自验证用户 Token。`ProjectConfiguration` 保留严格 JSON 输入配置，不再包含手写路由。运行时 Gateway 通过 Nacos 发现 `project-service`；开发者需将服务注册在联调所用 namespace，不能用静态下游地址替代发现。

准备独立 Kafka Topic `saas.forge.<environment>.project-service.events`：Example 身份仅需生产该 Topic，Audit 身份需消费该 Topic、使用 `audit-service.example-events` group，并生产 `saas.forge.<environment>.audit-service.example-isolations` 隔离 Topic。准备完成后，通过 Audit 的受控运行配置启用 `saas.forge.audit.example-consumer.enabled=true`，启用后 readiness 必须等待 Example 分区。默认不启动这个新消费者，避免尚未准备 Topic / ACL 的既有环境持续消费失败；未启用不能声明审计接入完成。Kafka 凭据使用 Secret / 受限文件注入。当前仓库没有自动发布这些环境 ACL，也没有臆造 staging/prod 配置；接入已有环境前必须通过该环境的受控流程准备 Topic、身份和 ACL。Audit 需执行新前向迁移 V6，Example 需执行 V9、V10；V10 将 Outbox 的 UPDATE 权限收窄为投递状态列，事实正文和来源不可改写。

成功写操作在业务事务内追加不可变事实；业务或 Outbox 失败一起回滚，幂等重放不追加。事实仅含 Tenant、Identity、Membership、资源、父 Project 的 ID 与资源版本，删除事实的版本为被删除的版本。后台发布器在短数据库事务中领取独立租约令牌，再在事务外发送 Kafka；收到确认后标记完成，失败记录安全异常类别并按指数间隔重试（最多间隔 60 秒）。确认丢失可能重复投递，Event ID 和正文保持不变，Audit 提交去重记录后才确认消息。永久失败需要运维处置，发布器不会凭空宣称事件已完成。Outbox 是服务内部投递控制表，不是租户公开查询表；运行身份可发布所有租户的已提交事实，业务资源表的 FORCE RLS 边界不变。

三个服务使用根 POM 管理的官方 OpenTelemetry 集成。HTTP SERVER、事实 PRODUCER 和 Audit CONSUMER 均产生实际 Span，W3C 上下文跨 HTTP、Outbox 和 Kafka 传播。使用外部运行配置设置 `management.opentelemetry.tracing.export.otlp.endpoint` 和 `management.tracing.sampling.probability`；Endpoint 属于部署拓扑，敏感认证材料不能提交或写入 Nacos。默认 Collector 的 debug exporter 用于接收诊断，不提供 Trace 查询 UI。

结构化标准输出使用日志 Schema 的白名单：HTTP 只记录路由模板、方法、状态和耗时，事实只记录固定事件码与 Trace / Span ID。框架原始消息、MDC、异常消息和堆栈不直接输出，避免凭据或业务正文泄露；因此诊断保留异常类型与固定失败码，不保留原始错误文本。安全拒绝和失败不采样丢弃。后续如需增加诊断字段，应先扩展安全白名单和负向验证。

`uv run --no-project --with "jsonschema[format-nongpl]==4.25.1" bash scripts/verify-example-pipeline.sh` 是隔离后端诊断入口：使用本轮制品、受控 JWKS / Simple Discovery 夹具、真实 PostgreSQL / Redis / Kafka / Collector。打包和启动测试子进程仅服务于诊断，日常开发仍在 IDE 原生 Run / Debug。jsonschema 仅为诊断测试工具，不进入应用运行时；也可在个人虚拟环境准备相同版本后直接执行脚本。脚本校验三个服务实际 JSON 输出、Schema 条件字段、敏感值反向场景与六条完整 Span 父子链。该入口没有真实 IAM 登录、Nacos、HTTPS 浏览器拓扑或 Remote，不能用来勾选阶段 3 产品验收。当前结果和未执行项见 [接入验证记录](../../docs/acceptance/example-gateway-observability-acceptance.md)。
