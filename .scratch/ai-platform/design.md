# AI 平台设计与交接

Status: draft（交接给 `ai-platform` 仓库）

本文件记录 2026-11-14 设计拷问确认的 AI 平台架构与落地顺序。术语与 ADR 在 `ai-platform` 仓库建立后迁入该仓库；本文只作为交接基线，不进入 saas-forge 的产品文档。

## 1. 定位

面向租户内部员工的一方 AI 工作台：助手会话、Skill、MCP、Prompt、模型选择。不是终端客户客服，也不是平台运营工具。

## 2. v1 范围

- 助手：会话列表、聊天、流式输出、模型选择
- Skill：平台目录 + 租户启用
- MCP：平台审核目录 + 租户启用，AI 服务代理连接
- 模型：平台模型目录（DeepSeek + OpenAI 兼容端点）
- Prompt：平台预置 + 租户版本化
- 审计、token 配额、角色模板随 v1 一起交付

不在 v1：

- 知识库（v1.5 独立立项）
- Squad、主动智能、消息回执、AI 应用/模板（v2）
- 面向终端客户的外部助手
- 会话分支/fork、按用户 token 配额

## 3. 架构

```text
Sub-App (Vue, MF) ── /api/ai/** ── Shell ── Gateway ── AI Business Service (Java)
                                                        │
                                                        ├─ AI DB（独立 schema，平台级 + 租户级表）
                                                        ├─ IAM：租户、权限、审计、Plan 配额
                                                        └─ Runner Pool（Node/DSH，内网 HTTP/SSE）
                                                              └─ 每租户一个 DSH 进程 + 独立 home
```

- 浏览器只访问 Gateway；Runner 不对外暴露。
- AI 服务是唯一业务入口；用户 Token 不进 Runner。
- DSH 只负责 agent loop，不承担产品数据、配额、审计与租户隔离。

## 4. 仓库布局

```text
ai-platform/
  service/    Java，Forge SDK，AI 业务域与平台接入
  runner/     Node/TS，DSH adapter（AgentRuntime 端口实现）
  sub-app/    Vue，sub-app-kit，助手 + 管理页
  deploy/     runner 镜像、MCP 目录、Helm/Compose
  docs/       GLOSSARY.md、adr/
```

## 5. 已定决策

| 主题 | 决策 |
|---|---|
| 仓库 | 独立 `ai-platform`，不进 saas-forge monorepo |
| DSH 角色 | 后端 agent runtime，由 AI 服务启动/隔离/路由 |
| DSH 版本 | 固定版本；只依赖 SDK JSON-RPC、MCP、Skill 文件；契约测试 + N-1 + 金丝雀 |
| 会话模型 | 一个 Conversation = 一个 DSH session；transcript 以 AI DB 为权威；v1 不做分支 |
| 数据边界 | `forge` 库独立 schema；平台级目录表与租户级业务表分开；SDK 租户隔离 |
| 租户删除 | 订阅 `TenantDeleted`，清理会话、消息、Prompt、Skill、MCP 配置、用量与 runner home |
| 留存 | 会话默认 90 天，可按 Plan/租户配置；用户可删除自己的会话 |
| Runner 拓扑 | 同集群独立 Deployment；每租户一个 DSH 进程；空闲 15 分钟回收；池满 429 |
| Runner 通信 | 内网 HTTP/SSE；AI 服务是唯一调用方 |
| 模型 provider | DeepSeek 官方 + OpenAI 兼容端点 |
| MCP | 平台审核目录；租户启用；AI 服务代理连接；Runner 不直连 |
| 工具授权 | AI 服务签发短期上下文；工具经 AI 服务代理校验 RBAC/ABAC；v1 只读 |
| token 配额 | Plan 增 `tokenQuota`；AI 订阅 `TenantPlanChanged`；超限拒绝新回合；用量回传平台 |
| 审计 | 会话开始/结束 + 工具调用 + 配置变更；不逐条消息、不记内容 |
| 密钥 | 平台密钥管理（K8s Secret/Vault）注入；AI DB 只存引用 |
| 流式 | SSE；前端 fetch stream，带 Authorization |
| 失败重试 | 模型瞬时错误有限重试；工具失败交回模型；Runner 崩溃重建会话；写操作不自动重试 |
| 搜索/导出 | PG trigram 子串搜索；会话导出 Markdown/JSON |
| 用量 | 每回合记录 token 用量；按租户/月聚合；每日回传平台 |
| 配置版本 | Prompt/Skill/MCP 草稿 → 发布版本 → 历史 → 回滚；模型目录无租户版本 |
| 模型切换 | 会话内可按回合切换，记录当前模型，历史不改写 |
| 角色 | 平台预置 `ai-user` 与 `ai-admin`，租户可复制自定义 |
| v1.5 | 知识库独立立项；AI 应用/模板后置到 v2 |

## 6. 平台侧前置（saas-forge）

1. **Plan token 配额**：Plan 增 `tokenQuota`，随 `TenantPlanChanged` 事件下发；参照 storage quota 的现有模式。
2. **审计动作**：AI 的 action 命名与查询支持；`forge-sdk-audit` 已支持自定义动作，补分类与文档。
3. **Permission Code / Manifest**：外部服务的 Manifest 上报与权限码唯一性已支持；动作词白名单补 `use` 等 AI 动作。
4. **Feature Pack**：为 AI 增加功能包，控制租户是否可见/可用。
5. **SDK 发布**：AI 依赖发布快照；每日集成构建验证兼容性。
6. **租户删除**：复用现有 `TenantDeleted` 事件，不新增平台接口。

## 7. `ai-platform` 内部 ADR 候选

1. DSH 作为可替换的 Agent Runtime：`AgentRuntime` 端口、固定版本、契约测试。
2. Conversation 与 DSH Session 一对一：AI DB 是 transcript 权威。
3. 工具与 MCP 经 AI 服务代理：短期上下文 + RBAC/ABAC，用户 Token 不进 Runner。
4. Runner 池与每租户进程：独立 Deployment、内网 HTTP/SSE、空闲回收。
5. 密钥由平台密钥管理注入：AI DB 只存引用。
6. 会话留存与租户删除：90 天默认、删除路径、审计元数据保留。

## 8. 落地顺序

### 阶段 0：平台前置（saas-forge）

- Plan `tokenQuota` 与事件下发
- AI 审计动作与文档
- 权限动作词 `use` 与 Manifest 校验
- AI Feature Pack
- 发布 SDK 快照

验收：AI 服务可以用快照接入租户、权限、审计、配额，不需要平台内部接口。

### 阶段 1：骨架（ai-platform）

- 仓库与 CI（Java、Node、Vue 三套构建）
- Java 服务用 `forge-service-archetype` 生成，接入 SDK
- Runner 实现 `AgentRuntime` 端口，能启动 DSH、建会话、流式返回
- Sub-App 用 `sub-app-kit` 生成，注册 Manifest 与网关路由

验收：一个租户内可以完成一次“建会话 → 发消息 → 流式回复 → 审计与用量落库”。

### 阶段 2：v1 功能

- 会话列表/聊天/模型选择
- Prompt、Skill、MCP 管理页
- 模型目录（只读）与租户选择
- token 配额、审计、角色模板
- 配置版本与发布、回滚

验收：租户管理员能配置 Prompt/Skill/MCP，普通成员能使用助手，配额与审计可查。

### 阶段 3：加固

- 失败重试、Runner 崩溃重建、429 排队
- 会话搜索/导出
- 租户删除清理、留存策略
- 密钥轮换、MCP 目录审核流程
- 可观测性（用量、错误率、trace）

### 阶段 4：v1.5 知识库

- 文档解析、切分、pgvector、引用
- 作为独立模块立项，不复用会话表硬塞

## 9. 待 spec 阶段决定

- AI 服务的 API 形状与错误码
- 数据库表结构与索引
- Runner 的内部协议（请求/事件/取消/审批）
- Sub-App 页面简报与交互细节
- MCP 目录审核的后台流程
- token 计量的字段与回传频率
- 模型目录字段与能力标记
- 部署参数（副本、资源、网络策略）
- 角色模板的精确权限码

## 10. 拟迁入 `ai-platform/GLOSSARY.md` 的术语

- **AI Business Service**：承载会话、Prompt、Skill、MCP、模型目录与配额的业务服务。
- **Agent Runtime**：DSH 适配层，执行 agent loop 与会话。
- **Runner**：承载 Agent Runtime 的进程/部署单元，按租户隔离。
- **Conversation**：用户在 AI 工作台看到的一段连续对话。
- **Agent Session**：DSH 运行时的持久会话，一个 Conversation 对应一个。
- **Tool Proxy**：AI 服务中校验授权并转发业务工具调用的入口。
- **MCP Catalog**：平台审核过的 MCP 服务器目录。
- **Prompt Template**：可版本化、可发布的提示词模板。
