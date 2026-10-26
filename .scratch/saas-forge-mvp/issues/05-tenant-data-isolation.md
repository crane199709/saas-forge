# 05: 租户数据隔离

**What to build:** demo-service 提供一个「订单」资源的增删改查，开发者不写任何租户相关代码，SDK 就按 Token 中的 Active Tenant 自动隔离读写（ADR-0001、ADR-0007）。即使有人绕过 SDK 直接执行 SQL，PostgreSQL RLS 仍阻止跨租户访问。

**Blocked by:** 03 Tenant Realm 最小登录链路

**Status:** ready-for-agent

- [x] SDK 租户 starter 基于 MyBatis-Plus（3.5.16+）自动为查询、插入、更新、删除注入 `tenant_id`；可声明不隔离的全局表
- [x] 每次获取数据库连接时设置当前租户会话变量，业务表启用 RLS 策略
- [x] 主键由应用侧生成 UUIDv7（ADR-0014）
- [x] demo-service 用 Flyway 管理自己的 Schema，业务表同时启用 `ENABLE` 与 `FORCE ROW LEVEL SECURITY`，使表所有者也受 RLS 约束
- [x] 「当前租户 → 数据源」为扩展点接口，默认单数据源实现
- [x] SDK 提供的 Redis 访问自动加 `{service}:{tenant_id}:` 前缀
- [x] 异步线程与定时任务在未携带或声明租户上下文时显式失败，而不是以无租户身份执行
- [x] Testcontainers 测试：租户 A 的 Token 读不到、改不了租户 B 的订单；绕过拦截器直接执行 SQL 时 RLS 仍阻止跨租户读写；测试以运行账号连接（与生产一致），并验证迁移账号直连时 RLS 同样生效


## Comments

2026-10-26 实现与验收：
- `forge-sdk-core` 新增 `CurrentTenant`（线程绑定的 Active Tenant，刻意不用 InheritableThreadLocal）与 `UuidV7`；`forge-sdk-security` 的 `CurrentTenantFilter` 在验签后把 JWT 中的 `tenant_id` 绑定到请求线程。自定义 `SecurityFilterChain` 的服务需自行加入该过滤器。
- `forge-sdk-tenant`：`TenantLineInnerInterceptor` 注入 `tenant_id`，全局表用 `forge.tenant.ignore-tables` 声明；`UuidV7KeyInnerInterceptor` 为空的 UUID 主键生成 UUIDv7（MP 的 `ASSIGN_UUID` 只支持 String 主键）；`UuidTypeHandler` 补齐 MyBatis 缺失的 UUID 映射。
- `TenantSessionDataSource` 包装主数据源，每次取连接执行 `set_config('app.tenant_id', …, false)`，无租户时置空（连接池复用连接）。`unwrap(DataSource.class)` 返回底层连接池，供 Boot 推导 Flyway 迁移连接；直接从底层池取连接不会设置会话变量。`TenantDataSourceRouter` 为扩展点，默认 `SingleDataSourceRouter`。
- `TenantRedisTemplate` 键前缀 `{spring.application.name}:{tenant_id}:`；全局数据继续用 Boot 的 `StringRedisTemplate`。`execute(RedisCallback)` 直接操作连接不经过前缀。
- 异步：注册 `TaskDecorator`，`@Async`/应用任务执行器沿用提交线程的租户；定时任务需用 `CurrentTenant.run` 声明。未携带时 MyBatis-Plus 与 Redis 访问抛 `IllegalStateException`，RLS 在会话变量为空时不可见、不可写任何行。
- demo-service：`orders` 表（Flyway V2，`ENABLE` + `FORCE ROW LEVEL SECURITY`）与订单 CRUD，业务代码不含任何租户条件；跨租户访问统一返回 404。
- `TenantIsolationTest`（PostgreSQL + Redis Testcontainers，运行账号 `demo_app`）覆盖：A 的 Token 读不到、改不了、删不掉 B 的订单；原生 JDBC 绕过拦截器时 RLS 阻止跨租户读、改、删与伪造 `tenant_id` 插入（42501）；迁移账号 `demo_migrator` 直连同样受限；异步任务携带/未携带租户；Redis 前缀。
- 后端 `./mvnw clean verify` 全部通过（含 spotless 检查与 IAM、网关回归）。
