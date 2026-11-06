# 04: 禁止直接 @Scheduled 并迁移 platform 生命周期扫描

**What to build:** 按 [spec](../spec.md) 第 2、5 节，`forge-architecture` 新增规则禁止 SDK 之外使用 `@Scheduled` 与 `@EnableScheduling`；platform 的生命周期扫描改为 `@GlobalTask`（固定间隔）。

**Blocked by:** 02

**Status:** resolved

- [x] 架构规则：SDK 包之外出现 `@Scheduled` 或 `@EnableScheduling` 时架构测试失败；各服务的 `ArchitectureTest` 接入
- [x] `LifecycleSchedulingConfiguration` 改为 `@GlobalTask(name = "tenant.lifecycle-scan", fixedDelay = <scanInterval>)`，删除 `@EnableScheduling`
- [x] 保留 `purgeDue`、`remindDue` 中的条件更新
- [x] platform 不配置 `forge.business-zone` 也能启动（只用固定间隔）
- [x] 原有生命周期测试通过

## Comments

## Answer

- 新增共享 `scheduling()` 架构规则并在 `ForgeArchitectureTest` 执行，各服务与 SDK 已有的继承入口自动接入。SDK 包之外的原生调度注解、组合注解声明与使用、重复 `@Scheduled` 的 `@Schedules` 容器均被拒绝，SDK 基础设施豁免。
- platform 引入 `forge-sdk-scheduling`，生命周期扫描改为 `@GlobalTask(name = "tenant.lifecycle-scan")`。固定间隔通过 Bean SpEL 读取已绑定的 `LifecycleProperties.scanInterval()`，默认值仍只由 `@DefaultValue("1m")` 提供；删除原生 `@EnableScheduling` 与 `SchedulingConfigurer`。
- `TenantLifecycleService.purgeDue()`、`remindDue()` 及其条件更新未修改。共享测试时钟显式触发扫描，真实 PostgreSQL/Redis 与 HTTP 测试验证冷静期清理、到期提醒及 Global Task 指标；未配置 `forge.business-zone` 和扫描间隔时仍成功启动并注册一分钟间隔。
- TDD：直接调度场景先因缺少规则失败；组合/重复容器场景先出现 6 项漏检失败；组合注解声明自身再出现 2 项失败；platform 迁移前分别在原生 `@EnableScheduling` 架构违规与缺少 Global Task 执行指标处失败，修复后通过。
- 验证：`./mvnw -o -pl forge-architecture,forge-platform test` 全量通过（当时架构 51 项、platform 20 项）；补充边界后 `ForgeArchitectureRulesTest` 53 项通过，platform 定向架构与生命周期 5 项通过；最终全后端同 reactor 执行 `./mvnw -o test -Dtest=ArchitectureTest,ForgeArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`，23 个模块构建成功。
- 未发现 SDK 集成缺陷。Redis 不可用时跳过本轮扫描，按规格等待下一周期；业务条件更新继续承担事件单次发布的正确性保证。
