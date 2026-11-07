# 03: Operator 紧急清除

**What to build:** 提供 Operator 清除指定 Tenant 规则的接口。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 清除后立即生效，下一次请求不再受限
- [ ] 校验 Platform Realm 权限，Tenant Token 不能调用
- [ ] 记录 `tenant.ipAllowlistClear`，并要求填写原因

## Comments
