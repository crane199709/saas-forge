# 05: Sub-App 工作台

**What to build:** 按 [spec](../spec.md) 的“实现决策”，用 `sub-app-kit` 生成 AI Sub-App，实现会话列表、聊天、流式输出、模型选择与错误状态。

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] Sub-App 编码 `ai`，挂载到租户 Shell，路由与菜单由 Manifest 声明
- [ ] 会话列表：新建、重命名、删除、搜索入口
- [ ] 聊天区：消息列表、SSE 流式渲染、取消、重试、错误提示
- [ ] 模型选择器：只显示当前租户可用模型；切换写审计
- [ ] 工具调用展示：名称、状态、结果摘要
- [ ] 空状态、加载状态、断流提示、权限不足提示
- [ ] 组件测试覆盖会话列表、流式渲染、取消、错误；Playwright 覆盖主链路

## Comments
