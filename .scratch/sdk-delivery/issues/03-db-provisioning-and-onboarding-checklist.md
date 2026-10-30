# 03: 服务数据库准备脚本与平台侧接入清单

**What to build:** 把本地 compose 初始化中「为服务创建 schema、迁移账号与运行账号」的逻辑整理成可在任意环境重复执行的脚本，本地 compose 改为调用它。另写一份平台侧接入清单，列出新 Business Service 上线前平台要做的全部事项。见[规格 · 平台侧接入准备](../spec.md#平台侧接入准备)。

**Blocked by:** 02 网关按配置声明接入服务（清单中的网关步骤以 02 的配置为准）

**Status:** ready-for-human

- [x] 脚本参数：服务名、迁移账号密码、运行账号密码；可重复执行（已存在时不报错，不重置已有数据）
- [x] 脚本保证：两个账号都不是超级用户、没有 BYPASSRLS；迁移账号是 schema 所有者；运行账号只有数据读写权限、不是表所有者；不授予跨 schema 访问权限
- [x] 本地 compose 与主链路 E2E 改为调用该脚本创建现有服务的 schema，行为不变
- [x] 新增接入清单文档，逐项说明「做什么、在哪里配、怎么确认生效」：数据库准备、iam Manifest 上报令牌、RocketMQ Topic、Nacos 中的网关路由与服务前缀声明、OpenAPI 聚合、App Registry 登记与套餐开放（有 Sub-App 时）
- [x] 验证：对同一服务连续执行两次脚本均成功；脚本创建的运行账号绕过 SDK 直接执行 SQL 时，RLS 仍阻止跨租户读写（复用 `TenantIsolationTest` 的断言方式）；本地 compose 全新启动后各服务正常迁移


## Comments

### 2026-10-30 实现与本地验收

- 新增 `deploy/postgres/init/lib/provision-service.sql`，使用 psql 参数 `service`、`migrator_password`、`app_password`。服务数据库短名与发现服务名可以不同；连接目标由调用者明确指定。
- 重复执行保留现有数据与密码；事务内创建受限双账号、schema、现有表及未来表的读写权限。已有高权限角色、角色成员关系、schema 所有权冲突或运行账号持有对象时拒绝并回滚。共享 extensions 的只读使用延续原行为；已有环境的 PUBLIC 与历史授权须先由 DBA 审计。
- 本地 compose 与 E2E 的共同入口调用参数化脚本，iam/platform/demo 的账号和密码不变；30 个集成测试同步复制完整初始化目录，复用同一实现。
- 新增 [平台侧接入清单](../../../docs/service-onboarding-checklist.md)，覆盖数据库、Manifest 令牌、RocketMQ Topic、租户初始化服务登记、Nacos 网关路由/前缀、OpenAPI、App Registry 与套餐开放，每项说明位置和生效确认方法。
- 独立 PostgreSQL 17 容器验收：同一服务连续执行成功，已有表数据和账号密码保留；运行账号无建表/TRUNCATE/其他业务 schema USAGE；直接 SQL 跨租户查询/更新/删除均不可见或影响 0 行，跨租户插入 SQLSTATE 42501；同名超级用户导致失败且本次新建角色回滚。测试容器已清理。
- 定向 `TenantIsolationTest`、`DatabaseMigrationTest` 8 项通过；完整后端 `./mvnw -B verify` 447 项通过，0 失败、0 错误、0 跳过，覆盖编译、Testcontainers、ArchUnit、Spotless。首次完整验证发现格式问题，修正后全量重跑通过。日志 `/tmp/db-provision-full-verify.log`。
- `deploy/e2e/run.sh` 的 3 个真实浏览器场景全部通过；全新隔离 Compose 数据库上 IAM/platform/demo 正常迁移，前端类型检查和构建通过。运行目录 `deploy/e2e/run-Oy8wclVk`，汇总日志 `/tmp/db-provision-e2e.log`。脚本已清理本次进程、容器和卷。
- 验收使用与本地 compose 相同的初始化目录；未重置现有开发库，未启动整套本地 Nacos/S3/MQ compose，也未部署共享环境。E2E 关闭 MQ/Nacos，不证明清单在共享环境的配置发布结果。
- code-review 的 Standards / Spec 两轴串行审查均为 0 项待修复问题。状态转为 ready-for-human 供人工审阅。
