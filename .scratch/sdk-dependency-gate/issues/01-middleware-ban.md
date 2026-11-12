# 01: 业务工程中间件直引禁令

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在 `forge-dependencies` 统一配置 Enforcer，禁止业务工程直接依赖 Redis、RocketMQ、Nacos 客户端与 XXL-JOB。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `forge-dependencies` 配置 `bannedDependencies`，覆盖 Spring Data Redis、Redisson、RocketMQ 客户端、Nacos 客户端、XXL-JOB
- [ ] 需要中间件的模块或平台服务通过 `forge.middleware.skip=true` 显式豁免
- [ ] 豁免处写注释说明原因；豁免清单进入文档
- [ ] `validate` 阶段失败，信息包含被禁止的依赖与建议使用的 starter
- [ ] 测试覆盖：默认失败、显式豁免成功、传递依赖不被误拦
- [ ] 业务工程 archetype 生成后自动继承该禁令

## Comments
