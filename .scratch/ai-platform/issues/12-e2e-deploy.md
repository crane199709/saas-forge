# 12: E2E、部署与可观测性

**What to build:** 按 [spec](../spec.md) 的“实现决策”，完成主链路 E2E、runner 池部署、网络策略、可观测性与发布/回滚。

**Blocked by:** 05, 06, 07, 08, 09, 10

**Status:** ready-for-agent

- [ ] Playwright 主链路：登录 → 进入 AI 工作台 → 建会话 → 发消息 → 看到流式回答 → 刷新恢复 → 删除会话
- [ ] 权限与租户隔离 E2E：无权限不可见、切租户不串数据
- [ ] runner 池部署：副本、资源限额、网络策略（仅 AI 服务可访问）
- [ ] AI 服务部署：schema 迁移、密钥注入、网关路由、App Registry
- [ ] 可观测性：请求量、错误率、首 token 延迟、token 用量、runner 进程数
- [ ] 发布与回滚：镜像 tag、DSH 版本、契约测试门禁
- [ ] 文档：本地启动、部署、升级、故障排查

## Comments
