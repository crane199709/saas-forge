# 01: 规则管理、Redis 发布与全量恢复

**What to build:** 按 spec 第 2、4 节，提供规则读写接口并发布到 Redis。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 校验 IPv4、IPv6、CIDR、条数上限，拒绝 `/0`，并保存规范化后的结果
- [ ] 保存时必须包含当前 IP
- [ ] 发布与数据库的一致性策略与现有租户状态一致，Redis 失败时不留下不一致状态
- [ ] 启动时全量发布，未启用的 Tenant 写入明确的“无限制”值
- [ ] 记录 `tenant.ipAllowlistUpdate`，跨租户访问被拒绝

## Comments
