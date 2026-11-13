# AGENTS.md

基于 SaaS Forge 平台的 Business Service，由 `forge-service-archetype` 生成。适用平台版本：@project.version@。

## 编码规范

完整规则见 [docs/standards/business-service.md](docs/standards/business-service.md)，改代码前先读。要点：

- Java 的 `if`、`else`、`for`、`while`、`do-while` 语句体一律使用大括号，即使只有一条语句；由 Checkstyle `NeedBraces` 在 `validate` 阶段强制检查，覆盖生产与测试代码。
- 手写 SQL 只能写在 mapper XML，禁止 `@Select` 等注解、`QueryWrapper`、带 SQL 片段的 Wrapper 方法与 `JdbcTemplate`；仅允许 `BaseMapper` 主键 CRUD 与单表 `LambdaQueryWrapper`。
- 手写 SQL 用 SQL-99 标准写法，PostgreSQL 特性限规范白名单。
- Flyway `CREATE TABLE` 用 `COMMENT ON` 写表和全部字段的中文描述，`ADD COLUMN` 写新字段的中文描述；分区子表只注释父表。
- 中间件按规范通过 Boot Redis、Spring Cloud Nacos 与 Forge MQ / Scheduling starter 接入；`./mvnw validate` 检查原生客户端直引，业务服务保持默认门禁。
- 类按 `<根包>.<功能包>.<层>` 组织，层名限白名单，跨功能包只经由对方 `service` 访问数据。
- 对外接口与消息体禁止直接使用数据库实体，接口模型用 `record`，不引入 Lombok。
- 自定义配置用 `@ConfigurationProperties` 的 `record` 绑定，不用 `@Value`；随环境变化的地址与密钥不在代码中设默认值，漏配须启动失败。
- Controller 公开方法显式返回 `ApiResponse<T>` 信封，请求体用 `@Valid` 校验；业务错误抛 `ForgeException`，错误码取自功能包 `service` 层 `ErrorCode` 枚举，格式 `<领域>.<原因>`；HTTP 状态码如实返回，不用 204。
- 租户业务表带标准列 `created_at`/`updated_at`/`created_by`/`updated_by`/`deleted`，实体继承 `AuditableEntity`；删除一律为逻辑删除，唯一性只用 `WHERE NOT deleted` 的部分唯一索引。
- 敏感字段新增或变更前读规范「敏感字段登记」；声明变化时递增 `forge.manifest.version`。接入导出或日志时读规范「敏感字段导出与日志」，按列路径与安全参数契约传值。
- 按需启用 storage 的文件治理时，检查器显式声明覆盖用途；引用专用查询包含逻辑删除行并保留租户隔离，建立引用须在同一可写事务内先 `get(purpose, id)` 再写业务行，引用查询设置数据库超时；详见业务规范「文件保留与生命周期」。storage 同时需要 audit/scheduling 的 MQ、Redis 与 IAM 目录配置，按该节接入；默认 starter 不引入可选 storage 表或配置。
- 注释写「为什么」，Mapper 方法必须有文档注释。

## 命令

测试用 Testcontainers 启动 PostgreSQL 与 Redis，需要本机 Docker。

- `./mvnw validate`：检查 Java 语句体大括号与中间件依赖，不启动测试容器。
- `./mvnw verify`：编译并运行全部测试，含架构检查（`ArchitectureTest`）、表与字段注释检查和 SDK 表检查（`SchemaCommentTest`），最后做 Spotless 格式检查。提交前必须通过。
- `./mvnw spotless:apply`：按父 POM 的规则格式化 Java、`pom.xml` 与 `src/{main,test}/resources` 下的 XML；XML 规则文件是根目录的 `spotless-xml.prefs`，不在本工程修改。
- `./mvnw test -Dtest=<测试类>`：迭代时只跑受影响的测试。
- `./mvnw verify -DforkCount=N`：调整测试并行的 JVM 数，默认 2。
- `./mvnw spring-boot:run`：本地启动，所需环境变量见 `src/main/resources/application.yml` 中的注释。

## 测试

- 集成测试不自行声明容器，用 `SharedContainers`：每个测试类独立建库、独立 Redis 库，属性注册须带 `spring.data.redis.database`。
- 新增 SDK starter 时，若 starter 的 jar 中带 `io/saasforge/sdk/<模块>/schema.sql`，把它复制为新的 Flyway 迁移；漏了 `SchemaCommentTest` 会失败。

## 平台升级

升级平台版本时按平台 CHANGELOG 的升级说明跟进：通用规范有改动时，用新版本 archetype 生成一个工程，从中复制 `docs/standards/` 覆盖本工程的副本，并更新本文件的适用版本。
