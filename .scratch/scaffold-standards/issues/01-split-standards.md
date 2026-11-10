# 01: 拆分规范文档

**What to build:** 把现有两份规范拆成对外发布的通用部分与平台内部部分，并把脚手架同步规则写进 AGENTS.md。见规格第 2、4 节。

**Blocked by:** —

**Status:** ready-for-agent

- [x] 新增 `docs/standards/business-service.md`：业务服务必须遵守的规则（SQL 与持久层、SQL 写法与表注释、建表与标准列、服务包结构、配置读取、Java 模型、接口格式、注释、自动架构检查），不含 SDK 内部、iam/platform/gateway 专属规则
- [x] 新增 `docs/standards/sub-app.md`：页面流程、原型、组件选择、交互规则、说明文字、样式、走查清单，命令与路径改为生成工程中的写法（`lint:style`、`test:visual`），不引用 `/roles`、`packages/ui` 内部实现
- [x] 两份通用文档不链接 ADR、GLOSSARY、`.scratch` 或 monorepo 路径；原链接处的结论写进正文
- [x] `docs/agents/coding-standards.md`、`docs/agents/frontend-design.md` 只保留平台内部规则，开头引用通用部分；同一规则不在两处出现
- [x] `AGENTS.md` 调整摘要与链接，加入规格第 4 节的同步规则
- [x] 逐条核对：拆分前两份规范的每条规则在拆分后恰好落在一处

## Comments

规则归属核对（2026-11-10）：把拆分前两份规范的规则逐条摘成 191 个特征短语，脚本检查每条在拆分后的四份文档中恰好出现一次且落在预期文件，结果 0 失败；同一脚本确认两份通用文档没有 Markdown 相对链接，也不含 ADR、GLOSSARY、`.scratch`、`docs/agents`、`packages/`、`apps/`、`backend/`、`frontend/`、`/roles`、平台服务模块名等内部指向；内部文档开头均引用对应通用部分。

- 进入 `docs/standards/business-service.md`：SQL 与持久层（含 Mapper XML 约定、Flyway 与 `@Sql` 两类例外）、SQL-99 与白名单、表注释、建表与标准列（RLS 与 UUIDv7 的结论写进正文）、`MetaObjectHandler` 禁令、包结构与层白名单、配置读取、Java 模型、接口格式（信封字段写进正文）、网关的匿名与 `/internal/` 规则、注释、`ArchitectureTest`。
- 留在 `docs/agents/coding-standards.md`：格式化工具、SDK 豁免与 `deploy/` 脚本、SDK `schema.sql` 注释、IAM/platform 实体表、`addMapper` 陷阱、SDK 依赖、gateway 的 `WebFilter`/`ErrorWebExceptionHandler`、SDK 配置类位置、`forge.gateway.services` 与 iam 网关规则、SDK 与前端包文档注释、架构检查中 SDK/IAM 专属规则与全量命令。
- 进入 `docs/standards/sub-app.md`：流程三步、原型、组件、交互、说明文字、样式 token、走查清单；视觉用例约定为 `e2e/visual/`（假会话 + `page.route` mock），命令为 `pnpm lint:style`、`pnpm test:visual(:update)`，工单 03 按此落地。
- 留在 `docs/agents/frontend-design.md`：视觉参考、`/roles` 样板、monorepo 截图路径与「基线只在 macOS 生成」、`theme.ts` 的 `layoutTokens` 与 `h()` 内联样式。
- 改写处：通用文档把「先修改本节」改为「先向平台提出」；基线平台改为「团队固定在同一平台生成」；示例换成订单域。另把 ADR-0020 指向旧锚点的链接改到新文档。
