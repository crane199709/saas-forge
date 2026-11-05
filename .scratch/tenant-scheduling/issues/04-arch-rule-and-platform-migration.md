# 04: 禁止直接 @Scheduled 并迁移 platform 生命周期扫描

**What to build:** 按 [spec](../spec.md) 第 2、5 节，`forge-architecture` 新增规则禁止 SDK 之外使用 `@Scheduled` 与 `@EnableScheduling`；platform 的生命周期扫描改为 `@GlobalTask`（固定间隔）。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 架构规则：SDK 包之外出现 `@Scheduled` 或 `@EnableScheduling` 时架构测试失败；各服务的 `ArchitectureTest` 接入
- [ ] `LifecycleSchedulingConfiguration` 改为 `@GlobalTask(name = "tenant.lifecycle-scan", fixedDelay = <scanInterval>)`，删除 `@EnableScheduling`
- [ ] 保留 `purgeDue`、`remindDue` 中的条件更新
- [ ] platform 不配置 `forge.business-zone` 也能启动（只用固定间隔）
- [ ] 原有生命周期测试通过

## Comments
