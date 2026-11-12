# 01: 业务工程中间件直引禁令

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在 `forge-dependencies` 统一配置 Enforcer，禁止业务工程直接依赖 Redis、RocketMQ、Nacos 客户端与 XXL-JOB。

**Blocked by:** —

**Status:** resolved

- [x] `forge-dependencies` 配置 `bannedDependencies`，覆盖 Spring Data Redis、Redisson、RocketMQ 客户端、Nacos 客户端、XXL-JOB
- [x] 需要中间件的模块或平台服务通过 `forge.middleware.skip=true` 显式豁免
- [x] 豁免处写注释说明原因；豁免清单进入文档
- [x] `validate` 阶段失败，信息包含被禁止的依赖与建议使用的 starter
- [x] 测试覆盖：默认失败、显式豁免成功、传递依赖不被误拦
- [x] 业务工程 archetype 生成后自动继承该禁令

## Comments

## Answer

已在 forge-dependencies 的 validate 阶段加入原生中间件直接依赖禁令。MQ 与 Reference Data 按实际需要显式豁免并注明原因；业务 Boot Redis / Spring Cloud Nacos starter 保持可用。规范、archetype、Sub-App 脚手架提示、接入指南与 CHANGELOG 已同步。实现 9eb1344，合并 c81611a。

验证：26 模块 validate；12 项真实 Maven 行为样例；真实 archetype 生成工程默认成功、直引原生 Redis 失败；2 项证明豁免不绕过 SDK 白名单的交叉样例；Spotless、Sub-App 生成测试、类型检查、Prettier 均通过。持久 fixture 与完整依赖表由工单03补齐。
