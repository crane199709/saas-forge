# 01: iam 内部租户名单接口

**What to build:** 按 [spec](../spec.md) 3.2 节，iam 提供 `/internal/tenants?status=trial,active` 分页返回租户 ID，供 Tenant Task 拉取名单。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 按状态过滤、分页（游标或页码，按 ID 稳定排序），只返回租户 ID
- [ ] 位于 `/internal/` 下，网关拒绝外部访问；服务间认证方式与现有内部接口一致
- [ ] SQL 在 mapper XML 中，带 `NOT deleted`
- [ ] 测试覆盖：状态过滤、分页完整不重复、外部经网关访问被拒

## Comments
