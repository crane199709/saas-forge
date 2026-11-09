# 03: Operator 紧急清除

**What to build:** 提供 Operator 清除指定 Tenant 规则的接口。

**Blocked by:** 01

**Status:** resolved

- [x] 清除后立即生效，下一次请求不再受限
- [x] 校验 Platform Realm 权限，Tenant Token 不能调用
- [x] 记录 `tenant.ipAllowlistClear`，并要求填写原因

## Comments


## Answer

已提供 Operator 紧急清除指定 Tenant IP Allowlist 的入口：

- `POST /platform/tenants/{tenantId}/ip-allowlist/clear`（网关前缀 `/iam`）；请求 `{reason: string}`，成功返回 HTTP 200、`ApiResponse<Void>`。
- 仅接受 Platform Realm Token，并按数据库中的当前 Operator 状态与既有 `platform:tenant:manage` 权限校验；Tenant Token、匿名请求、无权限或停用 Operator 均不能清除。
- 原因必填，拒绝空白，最多 500 字符；成功记录 Platform Realm 的 `tenant.ipAllowlistClear`，包括 Operator、目标 Tenant 资源和去除首尾空白后的原因。
- 复用工单 01 的 `TenantIpAllowlistService.clear` 事务与同步 Redis 发布，不重复更新逻辑；紧急入口允许从旧规则外的 IP 操作。不存在、已删除目标返回 404。
- 成功后目标规则立即发布为 `*`；其他 Tenant 不变。真实 Redis 拒写时数据库回滚、旧发布值保留，且不记录成功清除审计。

验证：`OperatorIpAllowlistClearTest` 10 个 HTTP 集成用例及 `ArchitectureTest` 1 个检查全部通过；`spotless:apply` 与 `git diff --check` 通过。先执行未实现入口的 RED，再写入完整受控实现并 GREEN，随后逐片验证权限、Realm、原因、目标、审计与真实 Redis 故障行为。详细记录见 [validation-03.md](../validation-03.md)。

```sh
cd backend
./mvnw -o -pl forge-iam -am install -DskipTests
./mvnw -o -pl forge-iam test -Dtest=OperatorIpAllowlistClearTest,ArchitectureTest -DforkCount=1
./mvnw -o -pl forge-iam spotless:apply
```

IAM 模块全量测试由集成阶段统一执行一次；网关逐请求与 Playwright 流程分别由工单 02、04 及集成阶段验证。

已无冲突合入最新 integration `9b3e2fd`，保留两工单的安全配置；合并后架构检查 1/1 再次通过。
