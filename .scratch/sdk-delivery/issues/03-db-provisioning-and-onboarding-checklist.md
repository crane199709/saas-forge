# 03: 服务数据库准备脚本与平台侧接入清单

**What to build:** 把本地 compose 初始化中「为服务创建 schema、迁移账号与运行账号」的逻辑整理成可在任意环境重复执行的脚本，本地 compose 改为调用它。另写一份平台侧接入清单，列出新 Business Service 上线前平台要做的全部事项。见[规格 · 平台侧接入准备](../spec.md#平台侧接入准备)。

**Blocked by:** 02 网关按配置声明接入服务（清单中的网关步骤以 02 的配置为准）

**Status:** ready-for-agent

- [ ] 脚本参数：服务名、迁移账号密码、运行账号密码；可重复执行（已存在时不报错，不重置已有数据）
- [ ] 脚本保证：两个账号都不是超级用户、没有 BYPASSRLS；迁移账号是 schema 所有者；运行账号只有数据读写权限、不是表所有者；不授予跨 schema 访问权限
- [ ] 本地 compose 与主链路 E2E 改为调用该脚本创建现有服务的 schema，行为不变
- [ ] 新增接入清单文档，逐项说明「做什么、在哪里配、怎么确认生效」：数据库准备、iam Manifest 上报令牌、RocketMQ Topic、Nacos 中的网关路由与服务前缀声明、OpenAPI 聚合、App Registry 登记与套餐开放（有 Sub-App 时）
- [ ] 验证：对同一服务连续执行两次脚本均成功；脚本创建的运行账号绕过 SDK 直接执行 SQL 时，RLS 仍阻止跨租户读写（复用 `TenantIsolationTest` 的断言方式）；本地 compose 全新启动后各服务正常迁移
