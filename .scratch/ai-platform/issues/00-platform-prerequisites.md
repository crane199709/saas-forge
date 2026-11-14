# 00: 平台前置（saas-forge）

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在 saas-forge 交付 AI 服务接入所需的通用能力：Plan token 配额、AI 审计动作、权限动作词与 AI Feature Pack。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] Plan 增加 `tokenQuota`（nullable，NULL 表示不限）、校验与迁移，`COMMENT ON` 写中文描述
- [ ] `TenantPlanChanged` 增加 token 配额字段，随事件下发；现有订阅方保持兼容
- [ ] 平台侧 Plan API 与运营端套餐页支持配置 token 配额，并展示
- [ ] 确认 AI 审计动作分类与命名（会话、工具、模型、配置变更），写入审计文档
- [ ] 权限动作词白名单补充 `use` 等 AI 动作，并纳入 Manifest/目录校验
- [ ] AI Feature Pack 可通过 Plan 配置（Sub-App 编码 `ai` 与 AI 权限码）
- [ ] 发布 SDK 快照，供 `ai-platform` 依赖；每日集成构建验证兼容

## Comments
