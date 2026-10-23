# 租户数据采用共享 Schema + tenant_id + RLS 兜底隔离

每个服务拥有自己的 schema；在同一服务的 schema 内，所有租户共享同一组表，每张业务表带 `tenant_id`，由 SDK 自动注入租户过滤条件，并启用 PostgreSQL 行级安全（RLS）作为第二道防线，防止 SDK 漏注入时跨租户读写。由于 PostgreSQL 的 RLS 默认对表所有者与超级用户不生效，业务表必须同时启用 `FORCE ROW LEVEL SECURITY`，且执行 Flyway 迁移的账号（schema 所有者）与应用运行账号（仅数据读写）分离，两者都不得为超级用户或拥有 `BYPASSRLS`——否则 RLS 兜底会悄然失效。SDK 将「当前租户 → 数据源」抽象为扩展点，为将来大客户独立库预留空间，但第一期不实现。

## Considered Options

- 每租户独立 Schema / 独立库：隔离更强，但迁移、连接池与运维成本随租户数线性增长，第一期不需要。
