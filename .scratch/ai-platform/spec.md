# AI 平台 v1 规格

Status: ready-for-agent（交接给 `ai-platform` 仓库）

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准；AI 领域的新术语在 `ai-platform` 仓库建立后迁入其 `GLOSSARY.md`。平台边界由 [ADR-0028](../../docs/adr/0028-ai-capability-as-separate-business-product.md) 记录。设计基线见 [design.md](design.md)。

## 问题陈述

租户内部员工需要一个受租户隔离、受权限控制、可审计、可计量的 AI 工作台。当前平台没有 AI 能力：

- 员工只能使用外部 AI 工具，数据出平台，无法满足租户隔离与合规要求；
- 没有统一的 Prompt、Skill、MCP、模型治理，无法回答“谁在什么模型上用了什么工具”；
- 没有 token 配额与用量，AI 成本不可控；
- icube-base 的 AI 平台包含助手、知识库、Skill、MCP、Squad、模型、策略、Prompt、主动智能、回执、审计等十余项能力，全部自研成本高。

DSH 提供了成熟的 agent runtime（会话、工具、Skill、MCP、Agent Teams、模型适配、调度），但它是单用户 harness，不是多租户产品：没有账号与租户、没有知识库、没有 Prompt/模型管理、没有业务审计与配额，且会执行模型生成的命令。直接部署或 fork 都不适合作为 saas-forge 的 AI 平台。

## 解决方案

新建独立的一方案品 `ai-platform`：

- **Java AI Business Service**：承载会话、Prompt、Skill、MCP 目录、模型目录、配额与审计，接入 Forge SDK 与平台契约；
- **Node DSH Runner**：把 DSH 包装成可替换的 `AgentRuntime`，每租户一个隔离进程，内网 HTTP/SSE 供 AI 服务调用；
- **Vue Sub-App**：AI 工作台（会话列表、聊天、模型选择）与管理页（Prompt、Skill、MCP、模型目录）；
- **平台前置**：saas-forge 增加 Plan token 配额、AI 审计动作、权限动作词与 AI Feature Pack。

v1 交付助手、Skill、MCP、模型、Prompt 五项，以及配额、审计、角色模板。知识库（v1.5）与 Squad/主动智能/回执/AI 应用（v2）后置。

## 用户故事

1. 作为租户管理员，我想在套餐中启用 AI 工作台，以便决定哪些租户可以使用。
2. 作为租户管理员，我想把 AI 使用权限授予成员，以便控制谁可以进入工作台。
3. 作为租户管理员，我想指定 AI 管理员，以便由专人维护 Prompt、Skill 与 MCP。
4. 作为租户成员，我想打开 AI 工作台，以便用自然语言完成日常任务。
5. 作为租户成员，我想新建、重命名和删除自己的会话，以便管理对话。
6. 作为租户成员，我想在会话中连续提问，以便保留上下文。
7. 作为租户成员，我想看到助手逐字流式输出，以便不必等待完整回答。
8. 作为租户成员，我想中途取消生成，以便在回答跑偏时及时止损。
9. 作为租户成员，我想在会话内切换模型，以便按任务选择合适模型。
10. 作为租户成员，我想看到助手调用了哪些工具，以便判断答案依据。
11. 作为租户成员，我想在需要时批准敏感工具调用，以便保留控制权。
12. 作为租户成员，我想刷新或重新登录后恢复历史会话，以便不丢失上下文。
13. 作为租户成员，我想搜索历史会话，以便找到之前的答案。
14. 作为租户成员，我想导出会话为 Markdown 或 JSON，以便分享或归档。
15. 作为租户成员，我想删除自己的会话，以便清理敏感内容。
16. 作为租户管理员，我想配置本租户可用的模型，以便符合采购与合规要求。
17. 作为租户管理员，我想创建、编辑、发布和回滚 Prompt 模板，以便稳定助手行为。
18. 作为租户管理员，我想启用平台提供的 Skill，以便扩展助手能力而不写代码。
19. 作为租户管理员，我想从平台审核过的 MCP 目录中启用服务器，以便接入外部系统。
20. 作为租户管理员，我想看到 token 用量与剩余配额，以便控制成本。
21. 作为租户管理员，我想在配额耗尽时得到明确提示，以便及时调整套餐。
22. 作为审计员，我想看到会话开始/结束、工具调用与配置变更，以便追溯 AI 使用。
23. 作为审计员，我想确认审计中不包含消息内容与密钥，以便避免二次泄露。
24. 作为平台运营人员，我想维护模型目录与 MCP 目录，以便统一治理。
25. 作为平台运营人员，我想看到租户的 AI 用量汇总，以便运营与计费。
26. 作为平台运维，我想让 runner 按租户隔离、空闲回收、池满排队，以便控制资源。
27. 作为平台运维，我想让 runner 崩溃后会话可以恢复，以便用户不丢历史。
28. 作为开发者，我想让 DSH 升级只影响 runner 适配层，以便产品代码稳定。
29. 作为租户成员，我想在切换租户后看不到其他租户的会话，以便保证隔离。
30. 作为租户管理员，我想在删除租户时清理全部 AI 数据，以便满足合规要求。

## 实现决策

- `ai-platform` 是独立仓库，包含 Java 服务、Node runner、Vue Sub-App 与部署目录；不放进 saas-forge monorepo（ADR-0028）。
- AI 服务用 `forge-service-archetype` 生成，接入 `forge-sdk-tenant`、`security`、`audit`、`web`、`manifest`、`provisioning`、`scheduling`。
- 数据放在 `forge` 库的独立 schema：平台级表（模型目录、MCP 目录、Skill 目录）与租户级表（conversation、message、prompt、tenant_skill、tenant_mcp、usage）分开；租户表带标准列，走 SDK 租户隔离。
- 一个 Conversation 对应一个 DSH Agent Session；AI 服务 DB 保存完整 transcript，是产品的权威数据；DSH JSONL 只做运行期缓存，回合结束导出后清理。
- Runner 是独立 Deployment，AI 服务通过内网 HTTP/SSE 调用；每租户一个 DSH 进程、独立 home/workspace，空闲 15 分钟回收，池满返回 429 + `Retry-After`。
- 浏览器只与 Gateway 通信；AI 服务暴露 `/ai/**`，Sub-App 编码为 `ai`，由 App Registry 与 Resource Manifest 注册。
- 聊天使用 SSE：DSH stream → runner → AI 服务 → SSE；前端用 fetch stream 携带 Authorization，不用 EventSource。
- 工具与 MCP 经 AI 服务代理：AI 服务签发短期上下文（tenant、user、session、scopes），工具调用先校验 RBAC/ABAC 再转发业务 API；用户 Token 不进入 runner；v1 只开只读工具。
- MCP 服务器只能来自平台审核目录，租户只能启用；凭据由 AI 服务保管，runner 不直连外部 MCP。
- 模型目录由平台运营维护，v1 支持 DeepSeek 官方与 OpenAI 兼容端点；租户选择可用模型。
- Prompt、Skill、MCP 配置采用“草稿 → 发布版本 → 历史 → 回滚”，发布写审计；模型目录不做租户版本。
- 模型密钥与 MCP 凭据由平台密钥管理（K8s Secret / Vault）注入，AI DB 只存引用。
- Plan 增加 `tokenQuota`，随 `TenantPlanChanged` 下发；AI 服务按租户/月计量并在超限时拒绝新回合，用量每日回传平台。
- 审计动作覆盖会话开始/结束、工具调用、模型切换、Prompt/Skill/MCP 发布与回滚；不逐条消息、不记内容。
- 角色模板预置 `ai-user` 与 `ai-admin`，租户可复制自定义。
- 会话默认保留 90 天；租户删除订阅 `TenantDeleted`，清理会话、消息、Prompt、Skill、MCP 配置、用量与 runner home；审计元数据保留。
- 失败语义：模型瞬时错误有限重试；工具失败交回模型；runner 崩溃用 transcript 重建会话；写操作不自动重试；每个用户消息带幂等键。
- 搜索使用 PostgreSQL trigram 子串匹配；会话导出 Markdown/JSON。
- 会话内允许按回合切换模型，记录当前模型，历史不改写。
- `AgentRuntime` 是唯一 DSH 依赖面：固定 DSH 版本，只依赖 SDK JSON-RPC、MCP 与 Skill 文件；升级通过契约测试（初始化、建会话、流式、工具调用、取消、恢复、导出）验证。

## 测试决策

- 好的测试只验证外部行为：AI 服务的 HTTP/SSE 契约、会话与消息的持久化、权限与租户隔离、审计与用量、Sub-App 的页面行为；不直接断言 DSH 内部结构或 runner 实现。
- 最高接缝是 AI 服务的 HTTP API：用假的 `AgentRuntime` 覆盖会话、流式、工具调用、取消、失败与重试；这层同时覆盖服务、DB、配额与审计。
- Runner 单独用契约测试：真实 DSH 进程完成“初始化 → 建会话 → 流式 → 取消 → 恢复 → 导出”，作为升级门禁；不把 DSH 内部单测当产品测试。
- 平台集成用 Testcontainers：租户隔离、Plan 配额事件、审计落库，复用 `SharedContainers` 模式。
- Sub-App 用 Vitest + mocked TenantClient 覆盖会话列表、聊天、流式、错误与权限；主链路用一条 Playwright 用例覆盖登录、建会话、发消息、看到流式回答与历史。
- 参考现有测试：`demo-service` 的 HTTP 集成测试、`main-flow` E2E、`forge-platform` 的 Plan/Quota 测试、`NotificationCenter` 的组件测试。
- 权限与审计用真实 IAM 测试容器验证，不用 mock 绕过。

## 不在范围

- 知识库（v1.5）：文档解析、切分、向量检索、引用。
- Squad、主动智能、消息回执、AI 应用/模板（v2）。
- 面向终端客户的外部助手。
- 会话分支/fork、按用户 token 配额。
- 多厂商原生模型适配（Anthropic、Gemini 等）。
- 写操作工具与自动执行；v1 只读。
- 自建 DSH fork 或修改 DSH 内部代码。
- 移动端原生 App。

## 补充说明

- 跨仓库依赖：saas-forge 先交付 Plan token 配额、审计动作、权限动作词与 AI Feature Pack，并发布 SDK 快照；AI 仓库用快照接入，配每日集成构建。
- `ai-platform` 建立后，把 [design.md](design.md) 的术语迁入其 `GLOSSARY.md`，把 6 个 ADR 候选写成正式 ADR，并把本规格的 issue 迁入该仓库的 issue tracker。
- DSH 是开发者预览，固定版本与契约测试是硬约束；升级策略见 design.md。
- 本规格的拆分见 `issues/`，编号 00 为跨仓库平台前置。
