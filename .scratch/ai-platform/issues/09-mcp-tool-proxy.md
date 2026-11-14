# 09: MCP 目录与工具代理

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现平台审核的 MCP 目录、租户启用、凭据引用与只读工具代理。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] 平台级 MCP 目录：名称、URL、协议、允许工具、状态、审核信息
- [ ] 租户启用/停用；凭据由 AI 服务保管，runner 只拿到代理地址
- [ ] 工具代理：短期上下文（tenant、user、session、scopes），校验 RBAC/ABAC 后转发业务 API
- [ ] v1 只开只读工具；写工具默认拒绝
- [ ] 工具调用写审计；不记录参数中的敏感原文
- [ ] 测试覆盖：目录审核、租户启用、代理授权、只读限制、跨租户拒绝、审计

## Comments
