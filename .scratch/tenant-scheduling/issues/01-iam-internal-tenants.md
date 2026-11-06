# 01: iam 内部租户名单接口

**What to build:** 按 [spec](../spec.md) 3.2 节，iam 提供 `/internal/tenants?status=trial,active` 分页返回租户 ID，供 Tenant Task 拉取名单。

**Blocked by:** —

**Status:** resolved

- [x] 按状态过滤、分页（游标或页码，按 ID 稳定排序），只返回租户 ID
- [x] 位于 `/internal/` 下，网关拒绝外部访问；服务间认证方式与现有内部接口一致
- [x] SQL 在 mapper XML 中，带 `NOT deleted`
- [x] 测试覆盖：状态过滤、分页完整不重复、外部经网关访问被拒

## Comments

## Answer

已提供 `GET /internal/tenants?status=trial,active&page=1&size=100`：

- `status` 必填，支持租户生命周期状态的逗号分隔列表；空值或未知状态返回 `400 request.invalid`。
- `page` 默认 1，`size` 默认 20、上限 100，复用 SDK `PageQuery`；`data` 仅含 UUID 字符串数组，`meta.page` 提供 `number`、`size`、`totalElements`、`totalPages`。
- Mapper XML 按 `id ASC` 排序，分页与总数查询均带 `NOT deleted` 和相同状态条件。
- 复用现有 `ManifestProperties.tokens` 与 `ServiceTokenFilter`，仅配置的服务令牌可直连读取；网关现有 `/iam/internal/**` 拒绝规则保持生效。

验证：

- 按 TDD 逐片确认红灯并实现：目录未开放时 401、未分页时页结果超量、空状态未校验时 500；对应 HTTP 行为现均通过。
- `./mvnw -o -pl forge-iam test -Dtest=InternalTenantDirectoryTest`：4 项通过，覆盖状态/逻辑删除过滤、稳定分页完整不重复、参数校验、服务令牌边界。
- `./mvnw -o -pl forge-gateway test -Dtest=GatewaySecurityTest#tenantDirectoryIsNotExposedByGateway`：通过，无凭证、服务令牌、有效 Tenant Realm Token 均不能经网关读取目录。
- 交付前执行一次 `./mvnw -o -pl forge-iam,forge-gateway spotless:check test`，两模块全量与格式检查通过（含架构规则）。

沿用当前 offset 分页契约，不提供跨多次请求的目录快照；Tenant Task 执行前仍须按规格复查租户状态。
