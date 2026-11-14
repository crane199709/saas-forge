# 10: 配额、用量、审计与角色模板

**What to build:** 按 [spec](../spec.md) 的“实现决策”，消费 Plan token 配额、记录每回合用量、写 AI 审计，并提供 `ai-user`/`ai-admin` 角色模板。

**Blocked by:** 00, 02, 04

**Status:** ready-for-agent

- [ ] 订阅 `TenantPlanChanged`，保存租户 token 配额；超限拒绝新回合
- [ ] 每回合记录 token 用量（模型、输入/输出、工具调用），按租户/月聚合
- [ ] 每日用量回传平台；平台可查看租户汇总
- [ ] 审计：会话开始/结束、工具调用、模型切换、Prompt/Skill/MCP 发布与回滚；不记内容
- [ ] 角色模板 `ai-user`、`ai-admin` 随 Manifest 预置；权限码与页面一致
- [ ] 测试覆盖：配额耗尽拒绝、用量记录、事件乱序、审计落库、角色授权

## Comments
