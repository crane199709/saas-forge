# 01: 运维通道基座

**What to build:** 按 spec 第 2 节，建立 SDK 运维接口的令牌校验与平台侧聚合调用，供本 feature 与 `task-run-history` 复用。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] SDK 模块校验每服务独立令牌，漏配启动失败，令牌不匹配返回 401
- [ ] SDK 依赖不产生循环，`forge-architecture` 规则通过
- [ ] 平台可获取服务列表，并经 `lb://<service>` 调用；单服务不可达只标记该服务
- [ ] 新增 `platform:ops:read`、`platform:ops:manage`，无权限的 Operator 与 Tenant Token 都被拒绝
- [ ] 经网关访问 `/internal/ops/*` 被拒绝
- [ ] 测试覆盖错误令牌、漏配令牌、服务不可达和权限不足

## Comments
