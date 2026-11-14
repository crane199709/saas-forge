# 11: 失败恢复、留存、搜索与导出

**What to build:** 按 [spec](../spec.md) 的“实现决策”，补齐重试/恢复、429、90 天留存、租户删除清理、搜索与导出。

**Blocked by:** 04, 05, 10

**Status:** ready-for-agent

- [ ] 模型瞬时错误有限重试；写操作不自动重试；幂等键防重复
- [ ] runner 崩溃后用 transcript 重建 DSH 会话并重试当前轮
- [ ] 池满 429 + `Retry-After`，前端可解释
- [ ] 会话默认 90 天留存，可按 Plan/租户配置；用户可删除自己的会话
- [ ] 订阅 `TenantDeleted`，清理会话、消息、Prompt、Skill、MCP 配置、用量与 runner home
- [ ] PostgreSQL trigram 搜索；会话导出 Markdown/JSON
- [ ] 密钥轮换后 runner 能拿到新引用，不需要重建产品数据
- [ ] 测试覆盖：重试、崩溃恢复、429、留存清理、租户删除、搜索、导出

## Comments
