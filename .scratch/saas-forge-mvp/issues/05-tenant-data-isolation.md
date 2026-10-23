# 05: 租户数据隔离

**What to build:** demo-service 提供一个「订单」资源的增删改查，开发者不写任何租户相关代码，SDK 就按 Token 中的 Active Tenant 自动隔离读写（ADR-0001、ADR-0007）。即使有人绕过 SDK 直接执行 SQL，PostgreSQL RLS 仍阻止跨租户访问。

**Blocked by:** 03 Tenant Realm 最小登录链路

**Status:** ready-for-agent

- [ ] SDK 租户 starter 基于 MyBatis-Plus（3.5.16+）自动为查询、插入、更新、删除注入 `tenant_id`；可声明不隔离的全局表
- [ ] 每次获取数据库连接时设置当前租户会话变量，业务表启用 RLS 策略
- [ ] 主键由应用侧生成 UUIDv7（ADR-0014）
- [ ] demo-service 用 Flyway 管理自己的 Schema，业务表同时启用 `ENABLE` 与 `FORCE ROW LEVEL SECURITY`，使表所有者也受 RLS 约束
- [ ] 「当前租户 → 数据源」为扩展点接口，默认单数据源实现
- [ ] SDK 提供的 Redis 访问自动加 `{service}:{tenant_id}:` 前缀
- [ ] 异步线程与定时任务在未携带或声明租户上下文时显式失败，而不是以无租户身份执行
- [ ] Testcontainers 测试：租户 A 的 Token 读不到、改不了租户 B 的订单；绕过拦截器直接执行 SQL 时 RLS 仍阻止跨租户读写；测试以运行账号连接（与生产一致），并验证迁移账号直连时 RLS 同样生效
