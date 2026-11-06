# 手写 SQL 默认符合 SQL-99，PostgreSQL 特性走白名单，暂不做多库适配

手写 SQL 有标准写法时一律用标准写法（`CAST`、`CURRENT_TIMESTAMP`、`TIMESTAMP WITH TIME ZONE`、`INTERVAL '1' DAY`、`POSITION`）；没有等价写法的 PostgreSQL 能力只有列入[编码规范白名单](../agents/coding-standards.md#sql-写法与表注释)才能使用。每张表和每个字段的中文描述用 `COMMENT ON` 写在迁移中，这本身也是白名单内的 PostgreSQL 语法。

只靠 SQL-99 换不来跨库兼容：分页、DDL 与类型在各库仍有差异，而租户隔离的 RLS 兜底（ADR-0001）、逻辑删除依赖的部分唯一索引（ADR-0016）和部门树的 `ltree` 都没有标准写法。收敛写法减少以后迁移时要改的 SQL，白名单同时就是一份迁移清单：每一项都是换库时要单独设计替代方案的工作。

本决定只改变 ADR-0016 中时间列的写法，语义不变。

## Considered Options

- 严格符合 SQL-99：要放弃 RLS、部分唯一索引与 `ON CONFLICT`，立即削弱租户隔离和并发保证，也仍然不能跨库运行。
- 只作建议：写法继续发散，以后无法判断哪些 SQL 依赖 PostgreSQL。
- 现在引入 Flyway `{vendor}` 目录、MyBatis `databaseId` 或方言抽象层：目标数据库尚未确定，抽象层无从验证。确定目标后再做，并为 RLS、`ltree` 与部分唯一索引逐库设计替代方案，其中租户隔离要修改 ADR-0001。
