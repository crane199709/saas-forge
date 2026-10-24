# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default triage roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Coding standards

手写 SQL 只能写在 mapper XML，禁止 `@Select` 等注解、`QueryWrapper`、带 SQL 片段的 Wrapper 方法与 `JdbcTemplate`；仅允许 `BaseMapper` 主键 CRUD 与单表 `LambdaQueryWrapper`。SDK 不允许出现模块级或包级循环依赖。对外接口与消息体禁止直接使用数据库实体，接口模型用 `record`，不引入 Lombok。注释写「为什么」，不设占比指标，SDK public API 与 Mapper 方法必须有文档注释。完整规则见 `docs/agents/coding-standards.md`。
