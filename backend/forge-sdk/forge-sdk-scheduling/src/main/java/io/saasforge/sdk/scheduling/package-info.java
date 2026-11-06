/**
 * 声明式任务调度。引入 starter 后，在 Spring Bean 的无参 void 方法上声明
 * {@link io.saasforge.sdk.scheduling.GlobalTask} 或 {@link io.saasforge.sdk.scheduling.TenantTask}；
 * 保留 Spring 代理调用，因此可与事务等 advice 共用。
 *
 * <p>服务必须配置 {@code spring.application.name}，用作 Redis 锁的服务隔离段；任务名在服务内唯一。
 * 锁键为 {@code forge:task:<service>:<name>}，租期 30 秒，每 10 秒按持有者令牌续期，释放同样校验令牌。
 * 其他实例或上一轮持锁时跳过当前触发，Redis 不可用时也跳过，不排队或补跑。
 * Redis 切换或进程长时间停顿仍可能造成短暂重复执行，业务任务必须幂等，并以条件更新等方式保证正确性。
 *
 * <p>cron 按 {@code forge.business-zone} 解释，有 cron 任务时漏配即启动失败；仅有固定间隔时不要求时区。
 * 任务触发与执行使用独立线程，固定间隔是触发之间的间隔；执行尚未结束时，新触发仍会计入跳过。
 *
 * <p>Tenant Task 每轮先通过 IAM 内部接口按每页 100 条拉取试用与正常租户的完整名单，
 * 再单线程逐租户复查 {@link io.saasforge.sdk.tenant.TenantStatusLookup}；状态不再为 trial/active 的租户跳过且不计失败。
 * 每次调用都在 {@link io.saasforge.sdk.core.CurrentTenant#run} 中进行，只绑定租户，不绑定 Membership 或操作人，
 * 审计列操作人为 NULL。单个租户失败时在租户 MDC 仍绑定时记录日志，继续下一个租户，不在本轮重试。
 * 租户目录不可用时本轮失败，下次触发重新获取名单；不提供跨页快照、补跑或失败租户重试。
 *
 * <p>声明 Tenant Task 时须配置 {@code forge.scheduling.iam-uri} 与 {@code forge.scheduling.token}，
 * 分别为 IAM 内部地址和本服务的 manifest 服务令牌；可配置为 {@code ${forge.manifest.iam-uri}} 与
 * {@code ${forge.manifest.token}}，复用已有服务配置。地址与令牌无默认值，漏配时启动失败；
 * 配置统一绑定为 Spring Bean，仅声明 Global Task 时允许这两项缺省，也不依赖 manifest starter。
 *
 * <p>Micrometer 指标以 {@code name} 为标签：{@code forge.task.executions} 为执行次数，
 * {@code forge.task.duration} 为执行耗时，{@code forge.task.skipped} 为锁被占用的跳过次数，
 * {@code forge.task.lock.failures} 为 Redis 抢锁失败次数；{@code forge.task.tenant.successes} 与
 * {@code forge.task.tenant.failures} 为 Tenant Task 的租户成功与失败次数，标签仅含任务名，不含租户 ID。
 * 日志记录任务开始、结束、耗时与失败。
 */
package io.saasforge.sdk.scheduling;
