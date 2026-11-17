# Business Service：0.1.0 → 0.2.0

本文适用于使用实际发布的 `0.1.0` archetype/SDK 创建的已有 Business Service。`0.1.0` 与 `0.2.0` 均不是产品正式版；发布前先用固定候选制品完成以下步骤，版本发布和共享环境切换另按发布流程执行。候选当前为 `0.2.0-SNAPSHOT`，发布后将其替换为实际发布坐标，不能将业务工程的版本机械改为 SDK 版本。

## 1. 固定基线与参考工程

提交或备份业务工程，记录工程提交、当前 Forge 依赖、业务版本、Manifest 版本及 Flyway 已执行记录。保留配置中的业务自定义项与环境变量约定，验收使用隔离数据库，禁止用共享开发库或线上数据库试错。

从目标版本的**实际 archetype 制品**在工程之外生成临时参考工程，生成参数使用已有工程的 `groupId`、`artifactId`、`package`、`serviceName`、`pathPrefix`，业务版本继续使用原值。Maven settings 中配置目标仓库；发布前候选消费方法见[候选制品说明](../../scripts/release/README.md)。对照两个工程审阅差异，逐项合并，不重新生成覆盖业务仓库。

```sh
mvn -B -s /path/to/settings.xml org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate \
  -DarchetypeGroupId=io.saasforge -DarchetypeArtifactId=forge-service-archetype \
  -DarchetypeVersion=0.2.0-SNAPSHOT \
  -DgroupId=com.example -DartifactId=orders-service -Dversion=7.4.0-SNAPSHOT \
  -Dpackage=com.example.orders -DserviceName=orders-service -DpathPrefix=orders \
  -DinteractiveMode=false -DarchetypeCatalog=local
```

`7.4.0-SNAPSHOT` 只是业务工程版本示例。平台依赖统一由 `forge-dependencies` 父 POM 的 `forge.version` 管理。`serviceName`、网关前缀、包名和业务坐标不因平台升级改变。Manifest 版本仅在权限、菜单、Data Resource、敏感字段等声明变化时递增；仅换 SDK 不要求递增。新参考工程默认菜单 `home` 是新建工程约定，已有服务按自己的 Sub-App 路由核对，不统一改成 `home`。

## 2. 必须合入的工程差异

| 文件/能力 | 0.1.0 → 目标版本的处理 |
| --- | --- |
| 父 POM/SDK | 更新 `io.saasforge:forge-dependencies` 父版本；直接声明的 Forge 依赖对齐同一版本，不留下旧版显式版本。保留业务坐标、业务版本、自定义属性、插件和 profiles。 |
| 默认必需依赖 | 增加直接依赖 `forge-sdk-ops`、`spring-boot-starter-validation`。默认不增加 IO/storage。 |
| 构建检查 | 保留父 POM 的 NeedBraces 与中间件直接依赖门禁；不关闭检查规避问题。Java 语句体补大括号，按门禁提示改用已有 SDK/受支持 starter。合入参考 POM 的 Spotless 插件与 `spotless-xml.prefs`，先 `spotless:apply` 再审阅差异。 |
| Mapper SQL | 合并参考工程 `now()` → `CURRENT_TIMESTAMP` 等当前 SQL-99 写法，保留业务查询；已执行迁移仍保持原样。 |
| 请求校验 | 为请求 record 声明符合业务约束的 Bean Validation，并在 Controller 请求参数加 `@Valid`；模板订单示例为标题非空、最长 200，金额非空且非负。保留业务 DTO 与业务语义。 |
| Wrapper | 旧发布模板未分发 Wrapper；从同版本参考工程补入或合并 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`，恢复 `chmod +x mvnw`；不覆盖业务 `.mvn` 中其他配置。 |
| 运维配置 | 增加 `forge.ops.token: ${FORGE_OPS_TOKEN}`。每服务使用独立令牌，Platform 的服务令牌映射使用对应值；漏配应启动失败。它与 `FORGE_MANIFEST_TOKEN` 的服务凭证用途不同，不能共用。 |
| 其余配置 | 对照参考 `application.yml` 合并说明及必配项，保留数据库账号、schema、Redis 库号、环境地址、现有自定义配置和覆盖文件。Manifest 仍使用已有服务身份，凭证按当前登记/轮换流程管理。 |
| 测试 | 合并参考工程的 `SharedContainers`、配置与测试辅助类；各类独立数据库/Redis 库，默认 `forkCount=2`。合并当前 HTTP、架构、租户、权限及 `SchemaCommentTest`，保留已有业务测试与路由断言；旧默认路径仍为 `overview` 时，把参考测试中的 `home` 预期改回原路径，不能为迁就新模板测试而修改业务 Manifest。`spring.data.redis.database` 不可漏注册。 |
| Agent 与规范 | 旧发布模板未分发 Agent/规范文件；补入或合并参考 `AGENTS.md` 的当前规则，保留业务工程约定；更新 `docs/standards/business-service.md` 规范副本。参考工程的主类名变化可按实际采用，已有主类可继续保留。 |

当前已存在的配置/SDK 功能按接入指南逐项核对。开启敏感字段脱敏、调度等可选能力时，再引入对应配置；没有声明的旧服务不为本次升级增加无关必配项。

## 3. 数据库：保留 V1—V7，只追加增量

真实 `0.1.0` 默认工程含以下七条迁移：`V1__init_schema`、`V2__orders`、`V3__orders_created_at`、`V4__messaging`、`V5__standard_columns`、`V6__order_categories`、`V7__order_data_permission`。全部保留原文件与字节内容。若业务已有后续版本，在自己的下一个可用版本号追加，本文 `V8/V9` 只表示没有后续业务迁移的固定样例。

**不能复制新参考工程合并后的 `V1__forge_sdk.sql`、`V2__example_orders.sql` 覆盖旧迁移，不能执行 `flyway clean`、`repair` 或重建数据库绕过校验。** 启动前后核对原有迁移版本、脚本名、checksum、安装时间与执行账号保持原样，新增迁移只执行一次。

1. 已有 `forge_outbox` 与 `forge_consumed_message`，从**目标 SDK JAR** 的 `io/saasforge/sdk/mq/schema.sql` 只复制 `forge_dead_event` 的建表、全部中文表/字段注释及两个索引，作为新迁移。不要重建已有两张 MQ 表。死信表为服务基础设施表，不启用 RLS。
2. 旧模板表/字段没有完整中文注释。以同版本参考工程中的 `COMMENT ON TABLE/COLUMN` 为依据，为仍使用的旧表追加注释迁移；模板固定样例涉及 `orders`、`order_notifications`、`order_categories`、`forge_outbox`、`forge_consumed_message`。业务已改名或删去示例时，按实际业务结构编写描述，不整段复制不存在的对象。此步骤让当前 `SchemaCommentTest` 验证全部表/字段。
3. 业务字段、索引或约束若已因业务开发变化，按实际结构补增量，不重复执行已完成的升级。不要为了符合新模板改变已有数据语义、覆盖业务领域模型或推测数据用途。

IO 与 storage 不属于默认 starter 的通过范围。使用它们时按[接入指南](../business-service-guide.md)的对应升级段，在独立场景验证原有任务/文件及业务行为；IO 的历史基线不是已发布的 `0.1.0`，具体来源和迁移检查见 [IO 专项升级清单](io-0.2.0.md)。未使用的可选能力不增加依赖、表或配置。

## 4. 验收与失败处理

先构建启动真实旧版服务，经业务 API 创建代表性数据并记录返回值；停止旧服务后，升级工程并启动到**同一隔离数据库**。至少验证：原记录可读、可继续修改、新记录可创建；匿名请求 401、无功能权限 403、跨租户读取/更新不可见；业务自定义行为与配置保留。真实数据库使用受限运行账号和独立迁移账号，不能用管理员账号掩盖 RLS/授权问题。

```sh
./mvnw -s /path/to/settings.xml spotless:apply
./mvnw -s /path/to/settings.xml verify
```

还须使用独立运维令牌读取现有 `/internal/ops/dead-events`，确认补表可实际查询；Tenant Token 不能调用该接口。网关应持续拒绝外部 `/internal/`。`/actuator/health` 成功不能替代数据、权限或死信表验证。MQ/Nacos 在隔离 HTTP 验收中关闭，真实 Broker 投递、服务登记、共享环境凭证和环境切换另行验收。

失败时保留完整 Maven 输出、服务启动日志、旧/新请求与响应、Flyway 错误和候选摘要。checksum 不符时恢复被改动的原迁移，核对业务基线；表/字段缺失时补新的增量迁移；不要修改历史或清库重试。升级期间暂停业务写入，部署前另确认业务自身回退策略；本文不承诺把已迁移数据库回退到旧服务。

## 5. 平台仓库中的可重复验证

入口建立固定样例，验证真实旧发布包和候选消费，不会对已有业务仓库执行自动覆盖。需要 Java 17、Node 24/npm、Docker、Python 3 和 `gh` 的包读取授权（CI 可传 `GITHUB_TOKEN`，权限 `packages: read`）。凭证仅在下载子进程内存中使用，不写进日志或 Maven settings；重定向后不转发 Authorization。只有第三方 Maven 缓存可复用，本机 `io.saasforge` install 缓存全部隔离。

```sh
bash scripts/release/candidate.sh use /path/to/fixed-candidate \
  bash deploy/upgrade-smoke/business-service.sh /path/to/new-evidence-directory
```

可追加一个已有真实发布缓存目录，目录须包含原始 `maven-provenance.json` 或入口生成的 `provenance.json` 及其记录的文件；先校验来源 URL 与 SHA-256，缺少的发布依赖仍只读下载。入口保留：发布文件来源/摘要、旧工程、候选参考工程、升级工程、业务文件和七条迁移的 SHA-256、旧/新服务 JAR、完整构建/测试日志与 HTTP/Flyway 证据。每次使用新证据目录；运行失败也保留诊断。Testcontainers 只回收本轮创建的容器，不接受外部数据库地址。

固定候选变更后应重新生成参考工程并复验受影响路径。消费脚本变化时保留制品来源身份，同时记录脚本提交和本轮结果；不能把消费脚本提交冒充打包源码提交。

## 已有快照接入工程

试点不能统一当作 `0.1.0`：ai-platform 基线 `c5cc07fd` 已使用 `0.2.0-SNAPSHOT`，父 POM、Ops、校验 starter、NeedBraces、scheduling IAM 引用及 MQ 必需表均已存在。按候选实际差异决定改动，不重复追加表或机械修改业务版本、Manifest 版本、迁移历史；未使用 IO/storage 时也不引入它们。

候选 Java 消费使用新 Maven 缓存，复用第三方依赖时排除全部 `io.saasforge`。将 `forge-release` 定向到固定候选 Maven 仓库，记录实际解析以及可执行业务 JAR 内 Forge 依赖的摘要。配套 IAM/Platform/Gateway 运行包可另行准备，避免准备平台运行环境时用源码 install 覆盖业务服务正在验收的候选 SDK。已有 scheduling 业务还须回归其 IAM 目录、租户边界与任务可观察结果；仅构建或健康检查不够。具体基线与范围见[AI 试点验收](../../.scratch/v020-upgrade/acceptance-07.md)。
