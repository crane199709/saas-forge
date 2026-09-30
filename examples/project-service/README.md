# Project Example

本模块实现 #212 的 Project 创建与详情，以及 #213 的列表与修改、#214 的 Task 创建/分页/详情，通过 Starter 的 `TenantContextAccessor` 获取可信租户身份。Project 属于业务应用；不会访问底座数据库。Project 删除及 Task 修改/删除尚未实现，当前不包含 Permission、Feature、Quota、Gateway 路由或浏览器闭环。

## HTTP 契约

正式契约：[openapi.yaml](openapi.yaml)。入口为 `POST /api/v1/projects`、`GET /api/v1/projects`、`GET /api/v1/projects/{projectId}` 和 `PUT /api/v1/projects/{projectId}`；Task 入口为 `POST/GET /api/v1/projects/{projectId}/tasks` 和 `GET /api/v1/projects/{projectId}/tasks/{taskId}`。只接受 Tenant User Access Token。

- 名称必填且非空白，最多 200 个 Unicode 码点；描述可省略或为 null，最多 2000 个码点。名称允许重复；名称和描述不能含 NUL 字符。空白不裁剪；省略描述与 null 视为相同请求。
- 创建返回 `201`、资源和规范路径 `Location`；读取返回 `200` 和当前 `version`。ID 为规范小写 UUIDv7，时间为 UTC RFC3339 三位毫秒。系统字段不可写，也不接受 Tenant 的请求输入。
- 不存在和其他 Tenant 的资源均返回 `404 / PROJECT_NOT_FOUND`。失败采用带 `traceId` 的 Problem Details，不回显 Token、输入或 SQL。
- Task 标题与 Project 名称使用相同的 200 码点必填边界，描述最多 2000 码点且可选；标题允许重复。创建只接受 `title`/`description`，系统固定 `status=TODO`，返回 `projectId`、UUIDv7、版本 1、时间和嵌套路径 Location。不存在/其他 Tenant 父资源为 `404 / PROJECT_NOT_FOUND`；存在父资源下不存在或父子不匹配的 Task 为 `404 / TASK_NOT_FOUND`。
- Task 集合以 ID 升序游标分页，`limit` 默认 50、最大 100。首页省略 `cursor`，末页 `nextCursor=null`、`hasMore=false`。游标绑定 Tenant、父 Project 和固定排序，24 小时过期；非法/过期/跨范围游标、未知或重复查询参数、非法 limit 返回 `400 / VALIDATION_FAILED`。不提供筛选或自定义排序。
- 修改及后续删除使用单个强版本 `If-Match: "<version>"`；缺失为 `428 / VERSION_REQUIRED`，过期为 `409 / RESOURCE_VERSION_CONFLICT`。修改用 PUT 替换名称和描述；省略/null 描述均清空。版本比较与更新原子完成，成功增加一次版本并维护更新时间。失败不改变数据；重新读取后用新键和最新版本恢复修改。不扩张 Gateway/CORS Header 白名单或暴露响应头。

- 列表按 UUIDv7 `id ASC` 排序，默认 50、最大 100；返回 `items/nextCursor/hasMore`，末页游标为 null，`hasMore=false`。游标绑定集合、Tenant、页大小和可见的末条 ID，继续翻页必须沿用原 limit；未知/重复查询参数、非法或不匹配游标返回 `400 / VALIDATION_FAILED`。静态数据无重复遗漏，不提供跨页事务快照。

## 原生启动

使用 JDK 17，在 IDE 直接运行 `io.saas.forge.example.ProjectApplication`。应用无需完整 Compose、打包 JAR、托管脚本或进程替换。

一次性准备与应用启停分离：

1. 在独立 PostgreSQL 18 实例或逻辑数据库边界，以管理员执行 [deploy/bootstrap.sql](deploy/bootstrap.sql)，创建 `project_db`、`project_migrator` 与 `project_app`。脚本只用于新边界，不能对已有数据库盲目重复执行。通过受限凭据渠道给两个登录角色配置各自秘密；SQL 不包含密码。
2. 使用独立迁移进程和 `project_migrator` 执行 Flyway，迁移位置为本模块 `src/main/resources/db/migration`。迁移进程独占迁移凭据；应用没有 Flyway 运行依赖，不读取迁移凭据。迁移账号的维护策略遵循 ADR 0017/0022。
3. 准备 Redis 中对应环境的 IAM 撤销索引和通过 Nacos 服务发现可用的 IAM JWKS 服务。依赖地址由开发者自行配置，可以使用已有基础设施，不要求本机容器。撤销索引未就绪时 Starter 会失败关闭，不得在真实环境伪造 ready 标记。
4. 在 Git 忽略的本地配置文件或 IDE 的外部配置中设置数据库 URL/`project_app` 凭据、Redis 地址及凭据、`security.jwt.issuer`、`saas.forge.environment`、Nacos discovery 地址/namespace/身份，以及本机监听端口。非敏感运行配置可按本地开发例外从外部文件加载。凭据仍使用环境变量、Secret 或受限文件，不提交模板。需要联调时使用 Nacos 发现 IAM，不能静态配置 IAM 下游地址。
5. 直接 Run/Debug 启动类。测试中用 Simple Discovery 定位受控 JWKS 设施，仅属于测试夹具，不能照搬为运行时服务发现配置。

当前 HTTP API 用于服务接入和后端验收；内部监听端口不是浏览器入口。浏览器仍须遵守既有受信 HTTPS、受控域名和 Gateway 拓扑，相关接入不属于 #212/#213/#214。

## 数据与幂等边界

`projects`、`tasks` 与 `project_write_results` 均含非空 `tenant_id`，强制 ENABLE/FORCE RLS，读写分别由 USING/WITH CHECK 限制。`project_app` 非 owner、无 BYPASSRLS/继承/迁移角色成员资格，只有所需 DML 权限，V5 仅追加 name/description/version/updated_at 的列级 UPDATE 权限。业务的读和写均在同一事务连接上调用事务级 `set_config('app.tenant_id', ..., true)`；提交、回滚或断连清理上下文。

Task 通过 `(tenant_id, project_id)` 复合外键引用 Project，拒绝跨 Tenant 引用及删除含 Task 的 Project，不使用级联删除。运行账号对 Task 仅有 SELECT/INSERT 权限，不能直接更改归属；V6 前向迁移保留 V1–V5。

写操作使用 `(identity_id, idempotency_key)` 唯一键，共用 Example 写操作键空间，不能把 Tenant 加入唯一键来放宽唯一性。事务级 advisory lock 使处理中重试立即返回 `409 / IDEMPOTENCY_REQUEST_IN_PROGRESS` 与 `Retry-After: 1`。SHA-256 指纹包含方法、规范路径和规范化请求体；修改还包含 If-Match 版本。跨 Tenant 的键冲突不会读取旧 Tenant 的正文。

完成响应和业务变更同事务提交。完成后 24 小时内重放原始状态、正文和 Location；格式/字段 `400` 不占键，未提交的基础设施失败回滚并释放键。Task 创建的不可见父资源 404 是真实稳定业务失败，记录并重放原 Problem 正文。稳定业务 `4xx` 使用同样的完成结果格式；创建操作在字段校验通过后没有自然业务拒绝（不限制同名、Quota 或成员权限），创建测试用受控完成记录验证既存稳定失败重放；修改测试通过真实版本冲突和不可见资源验证稳定失败重放。

同 Tenant 过期键在请求时清理。跨 Tenant 过期键因 RLS 仍不可见，在受控维护清理前继续返回键冲突；从不为重用键绕过 RLS。使用迁移账号定期执行 [deploy/expire-write-results.sql](deploy/expire-write-results.sql)，删除完成超过 24 小时的记录。维护频率决定跨 Tenant 过期键的最长额外占用时间；记录不应永久保留。当前键空间属于这个独立业务服务，不声称与其他独立服务数据库进行分布式幂等仲裁。

## 验证

在仓库根目录运行：

```sh
./mvnw -pl examples/project-service -am verify
```

使用 Docker 中的隔离 PostgreSQL 18/Redis 容器、随机端口和真实 HTTP 服务。公开 HTTP 测试通过受控 JWKS 服务提供的公钥验证签名 Token，并执行 Starter 的正式认证、声明和 Redis 撤销检查；数据库使用正式 bootstrap、Flyway 迁移和受限运行账号。补充数据库权限测试证明 RLS 独立于业务 WHERE 条件成立。

模块已加入根 Reactor，因此既有后端 CI 的根 `verify` 包含 Example。验收结果与限制见 [#212 验收记录](../../docs/acceptance/issue-212-acceptance.md) 、[#213 验收记录](../../docs/acceptance/issue-213-acceptance.md) 和 [#214 验收记录](../../docs/acceptance/issue-214-acceptance.md)。
