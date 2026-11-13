# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default triage roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Coding standards

- Java 的 `if`、`else`、`for`、`while`、`do-while` 语句体一律使用大括号，即使只有一条语句；由 Checkstyle `NeedBraces` 在 `validate` 阶段强制检查，覆盖生产与测试代码。
- 手写 SQL 只能写在 mapper XML，禁止 `@Select` 等注解、`QueryWrapper`、带 SQL 片段的 Wrapper 方法与 `JdbcTemplate`；仅允许 `BaseMapper` 主键 CRUD 与单表 `LambdaQueryWrapper`。
- 手写 SQL 用 SQL-99 标准写法，PostgreSQL 特性限编码规范白名单。
- Flyway `CREATE TABLE` 用 `COMMENT ON` 写表和全部字段的中文描述（含 `id`、`created_at` 等通用字段），`ADD COLUMN` 写新字段的中文描述；分区子表只注释父表。
- SDK 不允许出现模块级或包级循环依赖。
- 服务模块的类按 `<根包>.<功能包>.<层>` 组织，层名限白名单，跨功能包只经由对方 `service` 访问数据。
- 对外接口与消息体禁止直接使用数据库实体，接口模型用 `record`，不引入 Lombok。
- 自定义配置用 `@ConfigurationProperties` 的 `record` 绑定，不用 `@Value`；随环境变化的地址与密钥路径不在代码中设默认值，漏配须启动失败。
- 网关按服务前缀默认要求认证：匿名接口须在网关显式列出，服务间内部接口放在 `/internal/` 下并由网关拒绝。
- Controller 公开方法显式返回 `ApiResponse<T>` 信封，业务错误抛 `ForgeException` 并用功能包 `service` 层 `ErrorCode` 枚举中的 `<领域>.<原因>` 错误码，HTTP 状态码如实返回、不用 204。
- 租户业务表与有业务语义的实体表带标准列 `created_at`/`updated_at`/`created_by`/`updated_by`/`deleted`，实体继承 `AuditableEntity` 由 SDK 填充，删除一律为逻辑删除，唯一性只用 `WHERE NOT deleted` 的部分唯一索引，手写 SQL 自行维护标准列并带 `NOT deleted`；文件治理的引用专用查询按业务规范包含逻辑删除行。
- 注释写「为什么」，不设占比指标，SDK public API 与 Mapper 方法必须有文档注释。

完整规则分两处：业务服务通用部分见 `docs/standards/business-service.md`（随 archetype 发布），SDK 与平台服务的内部补充见 `docs/agents/coding-standards.md`。

## 前端界面

新增或调整页面前读 `docs/standards/sub-app.md`（随 sub-app-kit 发布）与 `docs/agents/frontend-design.md`（monorepo 内部补充），按页面简报、功能实现、设计走查三步完成；走查必须查看截图。

## 脚手架同步

`docs/standards/` 随脚手架复制进仓库外的生成工程，脚手架只带默认 starter 的必需项与规范要求的通用写法。改动以下任一项时，同一变更内同步更新 `docs/standards/`、`forge-service-archetype`、`sub-app-kit` 模板、`docs/business-service-guide.md` 与 `CHANGELOG.md`（写明改了哪些规则）：

- 编码规范（业务服务适用的部分）
- SDK 必需项：starter 需要的表、必配项
- 前端设计约定

## 测试

- 集成测试不自行声明 PostgreSQL、Redis 容器：forge-platform 继承 `PlatformIntegrationTest`（共享容器与 Spring 上下文，测试类共用一个库，不能假设库中只有本类数据）；其他模块用模块内的 `SharedContainers`（共享容器，每个测试类独立建库、独立 Redis 库，属性注册须带 `spring.data.redis.database`）。模块只有一个测试类用容器、或容器只有一个类用到（如 RocketMQ、S3）时，可留在类内。
- 用容器的模块默认 `forkCount=2`，可用 `-DforkCount=N` 调整。
- 迭代时用 `./mvnw -o -pl <模块> test -Dtest=<测试类>` 只跑受影响的测试；模块全量只在交付前跑一次。
- 本地 `~/.m2` 中的 SDK 制品可能落后于源码，单模块测试前先执行 `./mvnw -o -pl <模块> -am install -DskipTests`。
