# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default triage roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Coding standards

- 手写 SQL 只能写在 mapper XML，禁止 `@Select` 等注解、`QueryWrapper`、带 SQL 片段的 Wrapper 方法与 `JdbcTemplate`；仅允许 `BaseMapper` 主键 CRUD 与单表 `LambdaQueryWrapper`。
- 手写 SQL 用 SQL-99 标准写法，PostgreSQL 特性限编码规范白名单。
- Flyway `CREATE TABLE` 用 `COMMENT ON` 写表和全部字段的中文描述（含 `id`、`created_at` 等通用字段），`ADD COLUMN` 写新字段的中文描述；分区子表只注释父表。
- SDK 不允许出现模块级或包级循环依赖。
- 服务模块的类按 `<根包>.<功能包>.<层>` 组织，层名限白名单，跨功能包只经由对方 `service` 访问数据。
- 对外接口与消息体禁止直接使用数据库实体，接口模型用 `record`，不引入 Lombok。
- 自定义配置用 `@ConfigurationProperties` 的 `record` 绑定，不用 `@Value`；随环境变化的地址与密钥路径不在代码中设默认值，漏配须启动失败。
- 网关按服务前缀默认要求认证：匿名接口须在网关显式列出，服务间内部接口放在 `/internal/` 下并由网关拒绝。
- Controller 公开方法显式返回 `ApiResponse<T>` 信封，业务错误抛 `ForgeException` 并用功能包 `service` 层 `ErrorCode` 枚举中的 `<领域>.<原因>` 错误码，HTTP 状态码如实返回、不用 204。
- 租户业务表与有业务语义的实体表带标准列 `created_at`/`updated_at`/`created_by`/`updated_by`/`deleted`，实体继承 `AuditableEntity` 由 SDK 填充，删除一律为逻辑删除，唯一性只用 `WHERE NOT deleted` 的部分唯一索引，手写 SQL 自行维护标准列并带 `NOT deleted`。
- 注释写「为什么」，不设占比指标，SDK public API 与 Mapper 方法必须有文档注释。

完整规则见 `docs/agents/coding-standards.md`。

## 测试

- 集成测试不自行声明 PostgreSQL、Redis 容器：forge-platform 继承 `PlatformIntegrationTest`（共享容器与 Spring 上下文，测试类共用一个库，不能假设库中只有本类数据）；其他模块用模块内的 `SharedContainers`（共享容器，每个测试类独立建库、独立 Redis 库，属性注册须带 `spring.data.redis.database`）。模块只有一个测试类用容器、或容器只有一个类用到（如 RocketMQ、S3）时，可留在类内。
- 用容器的模块默认 `forkCount=2`，可用 `-DforkCount=N` 调整。
- 迭代时用 `./mvnw -o -pl <模块> test -Dtest=<测试类>` 只跑受影响的测试；模块全量只在交付前跑一次。
- 本地 `~/.m2` 中的 SDK 制品可能落后于源码，单模块测试前先执行 `./mvnw -o -pl <模块> -am install -DskipTests`。
