# 租户数据采用共享 Schema + tenant_id + RLS 兜底隔离

所有租户共享同一数据库与 Schema，每张业务表带 `tenant_id`，由 SDK 自动注入租户过滤条件，并启用 PostgreSQL 行级安全（RLS）作为第二道防线，防止 SDK 漏注入时跨租户读写。SDK 将「当前租户 → 数据源」抽象为扩展点，为将来大客户独立库预留空间，但第一期不实现。

## Considered Options

- 每租户独立 Schema / 独立库：隔离更强，但迁移、连接池与运维成本随租户数线性增长，第一期不需要。
