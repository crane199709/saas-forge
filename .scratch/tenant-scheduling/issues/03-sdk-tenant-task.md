# 03: Tenant Task

**What to build:** 按 [spec](../spec.md) 3.2–3.3 节，`@TenantTask` 在抢到锁后从 iam 拉取试用与正常租户，逐个在 `CurrentTenant.run` 中执行，失败隔离。

**Blocked by:** 01, 02

**Status:** resolved

- [x] `@TenantTask(name, cron | fixedDelay)`，互斥、跳过、指标规则同 Global Task
- [x] 每次执行分页拉取 `/internal/tenants?status=trial,active`
- [x] 单线程逐租户顺序执行；执行前用 `TenantStatusLookup` 复查，已非试用或正常的跳过，不计失败
- [x] 单个租户异常：记日志（带租户 ID）、计入失败指标、继续下一个；不在本轮重试
- [x] 以系统身份运行，审计列操作人为 NULL；不绑定 Membership
- [x] 增加租户成功与失败数指标；SDK 文档写明任务必须幂等
- [x] 测试覆盖：失败租户不影响其他租户、停用与过期租户不执行、执行中途被停用的租户被跳过、未在上下文外访问租户数据

## Comments


## Answer

已新增 `@TenantTask(name, cron | fixedDelay)`，复用 Global Task 的声明校验、服务内任务名唯一性、Redis 整轮互斥、异步执行、锁续期和通用指标：

- 每轮在抢锁后以 manifest 服务令牌读取 IAM `GET /internal/tenants?status=trial,active&page=1&size=100`，按响应信封 `meta.page.totalPages` 拉完名单；下轮重新读取，不缓存租户集合。
- 单线程逐租户在 `CurrentTenant.run` 中先调用 `TenantStatusLookup` 复查；仅 trial/active 执行，停用、过期、未发布状态及本轮中途停用均跳过，不计失败。`TenantStatusLookup` 公开并补充文档，依然不暴露 Redis 类型。
- 经 Spring 代理调用任务；单个租户异常在租户 MDC 仍绑定时记日志，计入失败后继续，不在本轮重试。任务不继承触发线程的 Membership 或操作人，审计填充为 NULL。
- 增加 `forge.task.tenant.successes`、`forge.task.tenant.failures`，仅以任务 name 为标签。SDK package-info 与注解文档写明顺序执行、失败隔离、幂等、锁的正确性边界和无补跑约定。
- Tenant Task 仅在被发现时绑定和校验 `forge.scheduling.iam-uri`、`forge.scheduling.token`；两项均无默认值，token 不出现在配置对象的 toString。业务服务可用 `${forge.manifest.iam-uri}`、`${forge.manifest.token}` 复用已有地址与服务令牌。只含 Global Task 的服务不要求目录配置，也不引入 manifest starter。
- 调度模块的 Redis 集成测试改为模块内 `SharedContainers`，每个测试类独立 Redis 库并注册 `spring.data.redis.database`。

验证：

- 按 TDD 逐片确认未提供 TenantTask、未绑定租户上下文以及缺少状态门禁时的红灯，再实现对应能力；目标测试覆盖分页、失败隔离、状态复查、两实例整轮互斥、下轮重新拉取目录、JDK/CGLIB 代理、无 Membership/actor、审计 NULL 与失败日志 MDC。
- 先执行源码依赖安装。并行 worktree 的安装会覆盖共享 `~/.m2` 中的旧 SDK，曾导致 TenantStatusLookup 的制品可见性不一致；此后目标测试改为同一 reactor 解析当前源码，未改业务实现规避。
- 已合入集成基线 `abed45c`，交付前一次执行 `./mvnw -o -pl forge-sdk/forge-sdk-scheduling -am verify '-Dtest=io.saasforge.sdk.scheduling.*Test,io.saasforge.sdk.tenant.*Test' -Dsurefire.failIfNoSpecifiedTests=false`：scheduling 36 项、tenant 3 项全部通过，Spotless 与最新 SDK 架构规则通过。

沿用 IAM offset 分页，不提供跨页目录快照；执行前仍实时复查状态。Redis 锁可能因故障切换或长停顿短暂失效，业务任务仍须幂等并自行保证正确性。未修改 platform、demo 或架构实现，未推送远端。

审查修正（2026-11-06）：

- `SchedulingProperties` 现通过 `@EnableConfigurationProperties` 注册为配置 Bean，注册器以 `ObjectProvider` 延迟消费；绑定允许缺省，仅发现 Tenant Task 时在启动阶段校验 IAM 地址与令牌。保留 Global Task 单独使用时免目录配置和 token 的 `toString` 脱敏，SDK 文档同步更新。
- 补全 IAM `TenantMapper.countByStatus` 的 Data Policy 文档：租户目录是全局表，不受 Data Policy 过滤。
- TDD 新增 Spring 任务 Bean 构造器注入目录配置的测试，先确认缺少配置 Bean 的红灯，再实现；Global/Tenant 注册 23 项通过。
- 交付前一次同 reactor 执行 `./mvnw -o -pl forge-sdk/forge-sdk-scheduling,forge-platform,forge-iam -am verify '-Dtest=io.saasforge.sdk.scheduling.*Test,io.saasforge.sdk.tenant.*Test,io.saasforge.platform.TenantLifecycleTest,io.saasforge.platform.ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false`：scheduling 37 项、tenant 3 项、platform 生命周期/架构 5 项全部通过，IAM 编译和所有相关 Spotless 检查通过。
