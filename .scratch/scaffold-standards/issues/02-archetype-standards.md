# 02: archetype 带入规范

**What to build:** `forge-service-archetype` 生成的工程带上 `AGENTS.md` 与 `docs/standards/business-service.md`，补齐通用写法，并增加 SDK schema 检查。见规格第 2、3、4 节。

**Blocked by:** 01

**Status:** ready-for-agent

- [x] 构建时把 `docs/standards/business-service.md` 复制进模板资源，不在 archetype 里另存一份
- [x] 模板新增 `AGENTS.md`：规则摘要、`./mvnw verify` 等命令、指向 `docs/standards/` 的链接
- [x] `archetype-metadata.xml` 中 Markdown 文件 `filtered="false"`；文档头部写入平台版本
- [x] 按规格第 3 节对比模板与 demo-service，补齐通用写法（至少请求体 `@Valid` 校验）；业务示范不进模板。在本工单 Answer 中列出对比结论与补齐项
- [x] 生成工程增加检查：POM 中每个带 `schema.sql` 的 SDK starter，迁移后其中的表都存在
- [x] `archetype-it` 断言 `AGENTS.md` 与文档存在、标题未被吞掉、版本已写入
- [x] `./mvnw -o -pl forge-service-archetype -am install -Parchetype-it` 通过

## Answer

### 对比结论（模板 vs demo-service）

| 写法 | 模板现状 | 结论 |
|---|---|---|
| 响应信封 `ApiResponse<T>`、201/200、分页 `meta` | 已有（`OrderController`） | 一致 |
| `ErrorCode` 枚举 + `ForgeException` | 已有（`OrderErrorCode`、`OrderService`） | 一致 |
| 请求体 `@Valid` 校验 | 缺：无 `spring-boot-starter-validation`，`OrderRequest` 无约束 | **已补齐** |
| 标准列与逻辑删除 | 已有（`V5__standard_columns.sql`，实体继承 `AuditableEntity`） | 一致 |
| `WHERE NOT deleted` 部分唯一索引 | 已有（`V6__order_categories.sql`） | 一致 |
| mapper XML 手写 SQL | 已有（`OrderMapper.xml` 等） | 一致 |
| 默认 starter 必需表 | `forge-sdk-mq` 的三张表已在 V4、V8 | 一致，新增自动检查 |
| 资源恢复、关闭订单、IO、附件、业务编号、定时任务、字典 | demo 独有 | 业务示范或可选能力，按规格不进模板 |
| `application.yml` 无默认值、环境变量注释 | 模板更严格 | 有意差异，保留 |

### 补齐项

- 模板 POM 加 `spring-boot-starter-validation`；`OrderController` 的创建与修改对请求体加 `@Valid`，`OrderRequest` 按 `orders` 表约束声明 `@NotBlank @Size(max = 200)`、`@NotNull @PositiveOrZero`。新增模板测试 `OrderApiTest` 覆盖 400 `request.invalid` 与逐字段 `errors`。
- `SchemaCommentTest` 新增 SDK 表检查：解析 POM 中的 `forge-sdk-*`，在 classpath 中找对应 jar 的 `io/saasforge/sdk/<模块>/schema.sql`，迁移后其中的表必须都存在（`SchemaCommentMapper.selectTableNames`）。已用删除 `V8__dead_event.sql` 的变异验证会失败。
- 构建时以 `@` 分隔符过滤把 `docs/standards/business-service.md` 复制到 `archetype-resources/docs/standards/`；原文头部新增 `> 适用平台版本：@project.version@`，生成工程中为实际版本。
- 模板新增 `AGENTS.md`（规则摘要、`./mvnw verify` 等命令、规范链接、适用平台版本）；`archetype-metadata.xml` 中 `AGENTS.md` 与 `docs/**/*.md` 为 `filtered="false"`。
- `archetype-it` 新增 `verify.groovy`：生成文档与仓库原文仅差版本号（标题未被吞、内容未被改写），`AGENTS.md` 标题、版本、命令、链接齐全。

### 遗留

- monorepo 中 `business-service.md` 的版本行显示为占位符 `@project.version@`；`sub-app.md` 的版本由工单 03 在 `create.ts` 中写入，两份文档写法可能不同。
- 生成工程未带 Maven Wrapper，`AGENTS.md` 注明无 wrapper 时用 `mvn`。

