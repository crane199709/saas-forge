# AI 能力作为独立业务产品，平台只提供通用能力

AI 工作台（助手、Skill、MCP、Prompt、模型目录）不放进 saas-forge 仓库，而是作为独立的 `ai-platform` 产品：Java AI Business Service + Node DSH Runner + Vue Sub-App。saas-forge 只补充通用能力：Plan 的 token 配额、AI 审计动作、Permission Code/Manifest 支持与租户删除清理；不在平台仓库里放 AI 业务代码。

原因：AI 是业务产品，发布节奏与平台不同；它是 Java + Node + Vue 的多语言组合，Maven reactor 装不下 runner；lowcode 已经验证了“外部业务服务 + Sub-App 通过 SDK 接入”的路径；独立仓库能逼出 SDK 的真实缺口，而不是用平台内部接口绕过。

## Considered Options

- 放进当前 monorepo：初期快，但平台仓库变成产品 monorepo，构建与发布耦合，长期拆分成本高。
- 先在当前仓库 incubate 再拆：拆出时历史、CI、依赖都要重做；只适合短期 spike。
- 把 AI 做成 Shell 内置页：把业务页面塞回平台，破坏 Shell/Sub-App 边界。

## Consequences

- 两个仓库需要 SDK 发布快照 + 每日集成构建；平台改动必须是通用能力，不能为 AI 开内部捷径。
- AI 的领域术语、ADR 与 issue tracker 放在 `ai-platform` 仓库；saas-forge 只记录平台侧边界与本 ADR。
- 平台需要先提供 token 配额、审计动作与 Manifest/权限码支持，AI 才能接入。
