# 01: 拆分规范文档

**What to build:** 把现有两份规范拆成对外发布的通用部分与平台内部部分，并把脚手架同步规则写进 AGENTS.md。见规格第 2、4 节。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 新增 `docs/standards/business-service.md`：业务服务必须遵守的规则（SQL 与持久层、SQL 写法与表注释、建表与标准列、服务包结构、配置读取、Java 模型、接口格式、注释、自动架构检查），不含 SDK 内部、iam/platform/gateway 专属规则
- [ ] 新增 `docs/standards/sub-app.md`：页面流程、原型、组件选择、交互规则、说明文字、样式、走查清单，命令与路径改为生成工程中的写法（`lint:style`、`test:visual`），不引用 `/roles`、`packages/ui` 内部实现
- [ ] 两份通用文档不链接 ADR、GLOSSARY、`.scratch` 或 monorepo 路径；原链接处的结论写进正文
- [ ] `docs/agents/coding-standards.md`、`docs/agents/frontend-design.md` 只保留平台内部规则，开头引用通用部分；同一规则不在两处出现
- [ ] `AGENTS.md` 调整摘要与链接，加入规格第 4 节的同步规则
- [ ] 逐条核对：拆分前两份规范的每条规则在拆分后恰好落在一处
