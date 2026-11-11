# 脚手架带入规范规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-10 的设计拷问逐条确认。

## 1. 背景与目标

后端 `forge-service-archetype` 与前端 `forge-create-sub-app` 生成的工程位于仓库外，只带了可自动执行的约束（`ArchitectureTest`、`SchemaCommentTest`、`forge-sub-app-lint`），不带编码规范与前端设计规范。业务团队和他们的 Agent 看不到这些规则，只能在审查或运行时踩坑。

现有两份规范 `docs/agents/coding-standards.md`、`docs/agents/frontend-design.md` 混有平台内部规则（SDK 依赖方向、`forge-sdk` 豁免、网关 `forge.gateway.services`、iam 权限、`packages/ui` 的 `h()` 内联样式、`/roles` 样板、monorepo 的视觉回归命令），并链接 ADR、GLOSSARY 和 `.scratch` 规格，原样复制到仓库外会失效。

脚手架本身也有脱节：archetype 已按「最小骨架 + 可选能力写在接入指南」组织，差异大多是有意的，但通用写法（如请求体 `@Valid` 校验）没跟上 demo-service；「新增 SDK 表时同步 archetype」只散落在各规格的通用验收里，没有写进 AGENTS.md，也没有自动检查。前端模板的 `HomePage` 不是规范要求的列表原型，生成工程没有 `lint:style` 和视觉回归工具。

目标：

- 生成的工程带上面向业务开发者的规范，Agent 与人读同一份。
- 规范只有一个来源，monorepo 与生成工程不各自维护。
- 脚手架补齐通用写法与前端走查工具，并有机制防止再次脱节。

非目标：

- 不把 demo-service 的业务示范（资源恢复、关闭订单、IO、附件、业务编号、定时任务）放进模板。
- 不提供文档同步命令，也不让生成工程从 `node_modules` 读取规范。
- 不改已生成工程，它们按 CHANGELOG 的升级说明自行跟进。
- 视觉回归不进模板冒烟。

## 2. 规范文档

### 拆分

- 新增 `docs/standards/business-service.md` 与 `docs/standards/sub-app.md`，作为对外发布的通用部分。内容取自现有两份规范中业务服务、Sub-App 必须遵守的规则。
- 通用部分不链接 ADR、GLOSSARY、`.scratch` 或 monorepo 路径；需要的结论直接写进正文。用到的术语在文内简短解释。
- `docs/agents/coding-standards.md`、`docs/agents/frontend-design.md` 只保留平台内部规则，开头引用对应的通用部分；规则不在两处重复。
- monorepo 的 `AGENTS.md` 摘要与链接随之调整。

### 进入生成工程

- 生成的工程带：
  - `AGENTS.md`：规则摘要、本工程的构建 / 测试 / lint 命令、指向 `docs/standards/` 的链接。
  - `docs/standards/business-service.md`（后端）或 `docs/standards/sub-app.md`（前端）。
- 文档在生成时复制，头部标注适用的平台版本。
- archetype：构建时把 `docs/standards/business-service.md` 复制进模板资源；Markdown 与 `AGENTS.md` 在 `archetype-metadata.xml` 中标为 `filtered="false"`，否则 Velocity 会把行首 `##` 当注释吞掉标题。规范文档原文不含版本占位符，生成时由 `archetype-post-generate.groovy` 在头部注入平台版本注释（与 sub-app-kit 写法一致）；模板 `AGENTS.md` 仍按模块现有的 `@` 分隔符资源过滤写入版本。
- sub-app-kit：把 `docs/standards/sub-app.md` 放进发布包（`files`），`create.ts` 在源码模式与发布模式下都复制到生成工程。

### 升级

- 某版本改动通用规范时，`CHANGELOG.md` 写明改了哪些规则；业务团队从新版本重新复制文档。

## 3. 脚手架内容

模板只收两类内容：

1. 默认 starter 要求的表、必配项与测试骨架。
2. 规范要求每个服务、每个页面都具备的写法：
   - 后端：响应信封、`ErrorCode` 枚举与 `ForgeException`、请求体校验（`@Valid`）、标准列与逻辑删除、部分唯一索引、mapper XML 手写 SQL。
   - 前端：列表原型 `PageHeader → PageToolbar → ListPage`、空值显示 `—`、时间用 `formatDateTime`、样式只用 `--forge-*` token。

可选能力继续写在 `docs/business-service-guide.md`，不进模板。

### 前端走查工具

- sub-app-kit 新增导出 `@crane199709/sub-app-kit/stylelint`，内容是现有 `frontend/stylelint.config.mjs` 的规则；monorepo 的配置改为引用它，规则只维护一份。
- 生成工程加 `lint:style` 脚本并接入 `build`。
- 生成工程附 `playwright.visual.config.ts` 与一个示例用例：在 `localStorage` 预置假会话，用 `page.route` mock 列表接口，覆盖接近真实长度的数据和空列表两种状态。不附基线 PNG，团队首次运行 `pnpm test:visual:update` 生成并提交。

## 4. 防止脱节

- `AGENTS.md` 写入同步规则：改动编码规范、SDK 必需项（表、必配项）或前端设计约定时，同步更新 `docs/standards/`、`forge-service-archetype`、`sub-app-kit` 模板、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- `archetype-it` 生成的工程增加检查：POM 中声明的每个 SDK starter，若 SDK 带 `schema.sql`，迁移后其中的表都必须存在。
- 不从 demo-service 自动生成 archetype。

## 5. 测试

只验证外部可观察的行为：

- archetype 生成测试（`archetype-it`）：生成工程 `verify` 通过，含新增的 schema 检查；`AGENTS.md`、`docs/standards/business-service.md` 存在且标题未被吞掉、版本已写入。
- `create.test.ts`：生成工程包含 `AGENTS.md`、`docs/standards/sub-app.md`、`lint:style` 脚本与视觉测试配置。
- 模板冒烟：前端增加 `pnpm lint:style`；后端沿用 `verify`。
- 前端模板页面按 `docs/agents/frontend-design.md` 的三步流程实施，走查必须查看截图。

## 6. 工单

| 工单 | 内容 | 依赖 |
|---|---|---|
| [01](issues/01-split-standards.md) | 拆分规范文档，加同步规则 | — |
| [02](issues/02-archetype-standards.md) | archetype 带入规范、补通用写法、schema 检查 | 01 |
| [03](issues/03-sub-app-kit-standards.md) | sub-app-kit 带入规范、stylelint、列表原型、视觉测试 | 01 |
| [04](issues/04-smoke-and-changelog.md) | 模板冒烟与 CHANGELOG | 02、03 |

02 与 03 可并行。

## 通用验收

- 遵守 `docs/agents/coding-standards.md` 与 `docs/agents/frontend-design.md`。
- 迭代时只跑受影响的测试；交付前跑相关模块全量。
