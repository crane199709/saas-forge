/**
 * 声明式任务调度。引入 starter 后，在 Spring Bean 的无参 void 方法上声明
 * {@link io.saasforge.sdk.scheduling.GlobalTask}；保留 Spring 代理调用，因此可与事务等 advice 共用。
 *
 * <p>服务必须配置 {@code spring.application.name}，用作 Redis 锁的服务隔离段；任务名在服务内唯一。
 * 锁键为 {@code forge:task:<service>:<name>}，租期 30 秒，每 10 秒按持有者令牌续期，释放同样校验令牌。
 * 其他实例或上一轮持锁时跳过当前触发，Redis 不可用时也跳过，不排队或补跑。
 * Redis 切换或进程长时间停顿仍可能造成短暂重复执行，业务任务必须幂等，并以条件更新等方式保证正确性。
 *
 * <p>cron 按 {@code forge.business-zone} 解释，有 cron 任务时漏配即启动失败；仅有固定间隔时不要求时区。
 * 任务触发与执行使用独立线程，固定间隔是触发之间的间隔；执行尚未结束时，新触发仍会计入跳过。
 *
 * <p>Micrometer 指标以 {@code name} 为标签：{@code forge.task.executions} 为执行次数，
 * {@code forge.task.duration} 为执行耗时，{@code forge.task.skipped} 为锁被占用的跳过次数，
 * {@code forge.task.lock.failures} 为 Redis 抢锁失败次数。日志记录任务开始、结束、耗时与失败。
 */
package io.saasforge.sdk.scheduling;
