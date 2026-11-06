# 02: forge-sdk-scheduling 与 Global Task

**What to build:** 按 [spec](../spec.md) 第 2、3.1、4 节，新 SDK 模块 `forge-sdk-scheduling` 提供 `@GlobalTask`（cron 或固定间隔）、Redis 互斥锁与不重叠执行、日志与指标。

**Blocked by:** reference-data/04（`forge.business-zone`）

**Status:** resolved

- [x] `@GlobalTask(name, cron | fixedDelay)`：`name` 必填且服务内唯一（重复时启动失败），`cron` 与 `fixedDelay` 二选一，`fixedDelay` 支持占位符
- [x] cron 按 `forge.business-zone` 解释；服务存在 cron 任务而漏配时启动失败，只有固定间隔时不要求
- [x] Redis 锁：`SET NX PX` + 持有者令牌，租期参考 30 秒、每 10 秒续期，释放时校验令牌
- [x] 抢不到锁或 Redis 不可用时跳过本次触发，分别计入跳过与抢锁失败指标；不补跑
- [x] 指标与日志：执行次数、耗时、跳过、抢锁失败，标签含 `name`
- [x] 模块依赖 `forge-sdk-tenant` 与 Redis，`forge-architecture` 的 SDK 依赖与循环规则通过
- [x] 测试覆盖：两个实例只有一个执行、上一轮未结束时下一次被跳过、续期使长任务不丢锁、令牌不符不能释放

## Comments

## Answer

已新增 `forge-sdk-scheduling`，接入 SDK 聚合 POM 与 BOM：

- `@GlobalTask` 在 Spring Bean 的无参 void 方法上注册，启动时先校验全部声明：任务名非空且唯一，cron/fixedDelay 二选一，间隔为正数。固定间隔支持占位符、Bean SpEL、ISO Duration、带单位时长与毫秒值；JDK/CGLIB 代理均通过代理调用，保留事务等 advice。
- cron 使用共用 `BusinessZoneProperties`，仅有 cron 任务时要求 `forge.business-zone`；固定间隔任务可不配置时区。
- 锁按 `forge:task:<spring.application.name>:<name>` 隔离，Redis `SET NX PX` 配合随机令牌，租期 30 秒、每 10 秒校验令牌续期；释放使用 Lua 比较令牌后删除。
- 触发与执行线程分离。锁被占用时计 `forge.task.skipped`，Redis 不可用时计 `forge.task.lock.failures`，均跳过当前触发、不排队补跑。执行计数与耗时分别为 `forge.task.executions`、`forge.task.duration`，均带 `name` 标签；记录开始、结束、耗时和失败日志。
- 公共文档写明任务须幂等、Redis 锁不能代替业务正确性保证。后续 Tenant Task 可复用同包的 `TaskRegistrar`、`TaskRunner`、`RedisTaskLock`。

验证：先安装当前源码依赖，再按片运行受影响测试；交付前仅运行一次 `./mvnw -o -pl forge-sdk/forge-sdk-scheduling verify`，19 项测试通过，含 SDK 架构规则与 Spotless。真实 Redis 覆盖双实例同时触发、上一轮未结束的新触发跳过、默认 30 秒租期的长任务续期、错误令牌既不续期也不释放、Redis 连接失败、任务异常后下一轮可执行；Spring 注册覆盖占位符、SpEL、时区、声明校验与两种代理。续期片曾在未实现续期时观察到 Redis TTL=-2 的红灯，实现后超过 31 秒仍持锁。

范围仅含工单 02；Tenant Task、架构禁止原生调度与 platform/demo 迁移留给后续工单。
