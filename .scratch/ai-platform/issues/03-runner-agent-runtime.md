# 03: Runner 与 AgentRuntime

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现 Node/TS runner，把 DSH 包装成 `AgentRuntime`：每租户进程、内网 HTTP/SSE、取消、会话导出与契约测试。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] runner 使用固定 DSH 版本与 `sdk` profile，启动/复用每租户 DSH 进程
- [ ] `AgentRuntime` 端口覆盖：初始化、建会话、发消息/流式、取消、导出、关闭
- [ ] 每租户独立 `DSH_HOME` 与临时 workspace，进程资源限额
- [ ] 内网 HTTP/SSE 接口，只有 AI 服务可调用；不暴露 shell/FS 工具
- [ ] 空闲回收与池满 429 的接口语义
- [ ] 契约测试：初始化 → 建会话 → 流式 → 取消 → 恢复 → 导出
- [ ] 记录 DSH 版本、启动参数与错误分类，供 AI 服务排障

## Comments
