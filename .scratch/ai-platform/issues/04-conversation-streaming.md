# 04: Conversation、消息与 SSE

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现会话与消息模型、AI 服务 API、SSE 流式输出、transcript 持久化与幂等。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] 会话表与消息表：租户、所有者、标题、模型、时间、标准列；消息保存角色、内容、工具调用摘要
- [ ] 创建/列表/重命名/删除会话；发送消息；取消生成
- [ ] SSE 输出：token、tool、done、error 事件；客户端断开触发取消
- [ ] 一个 Conversation 对应一个 DSH session；DSH 会话丢失时用 transcript 重建
- [ ] 用户消息幂等键，重复提交不重复执行
- [ ] 权限与租户隔离：只能访问自己的会话；跨租户不可见
- [ ] 集成测试：完整一轮对话、流式事件、取消、幂等、跨租户

## Comments
