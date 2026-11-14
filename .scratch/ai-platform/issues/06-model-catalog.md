# 06: 模型目录与租户选择

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现平台级模型目录、DeepSeek/OpenAI 兼容 provider 配置，以及租户可用模型选择。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 平台级模型表：provider、model id、base URL、能力标记、状态、上下文窗口
- [ ] 运营端模型目录维护；密钥只存引用，不存明文
- [ ] 租户可用模型选择，按 Plan/Feature Pack 裁剪
- [ ] 模型目录变更写审计；租户不能修改平台目录
- [ ] 测试覆盖：目录 CRUD、租户选择、禁用模型不可选、跨租户隔离、密钥不出现在响应

## Comments
