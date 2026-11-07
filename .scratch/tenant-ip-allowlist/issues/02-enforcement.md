# 02: 网关与 Token 签发校验

**What to build:** 按 spec 第 3、4 节，在签发 Tenant Token 和网关中执行校验。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 每个请求仍只多一次 Redis 往返，规则解析结果可按原始值缓存
- [ ] 不在列表内返回 403 `tenant.ipNotAllowed`，且不触发前端刷新循环
- [ ] 键缺失时拒绝，Redis 故障维持现有 503 行为
- [ ] 同一 Identity 进入其他 Tenant 不受影响
- [ ] Token 签发被拒时记录 `tenant.ipAllowlistDenied`
- [ ] 测试覆盖可信代理、IPv6 和 CIDR 边界，并记录上线顺序

## Comments
