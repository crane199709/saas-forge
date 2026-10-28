package io.saasforge.sdk.tenant;

import java.util.Objects;
import java.util.Set;

/**
 * 按租户状态拒绝写入的默认行为（规格 5.3）：当前租户处于停用、过期、归档或删除状态时，SDK 拒绝对租户表的 INSERT、UPDATE、DELETE，
 * 抛出 403 {@code tenant.writeBlocked}；查询不受影响。
 *
 * <p>状态取自 iam 按 platform 广播的 {@code TenantStatusChanged} 写入 Redis 的租户状态，与网关读取的是同一份。
 * 不由各服务各自订阅广播：同一服务的多个实例共用一个消费组，广播只会被其中一个实例收到。
 * 状态键缺失时放行：写入保护是网关之外的兜底，网关在键缺失时已拒绝成员请求；这里拒绝会让 Redis 数据被淘汰后
 * 开通、消息消费等系统写入全部失败。读取 Redis 失败时写入失败，不静默放行。
 *
 * <p>以下写入不受限制：全局表（{@code forge.tenant.ignore-tables}）、声明了 {@code @InterceptorIgnore(tenantLine = "true")}
 * 的 Mapper（如 Outbox 等 SDK 基础设施表）、未绑定租户上下文的写入，以及在 {@link #exempt(Runnable)} 中执行的写入。
 */
public final class TenantWriteGuard {
    /** 允许写入的租户状态：待开通（开通期间各服务初始化数据）、试用与正常。 */
    static final Set<String> WRITABLE = Set.of("pending", "trial", "active");

    private static final ThreadLocal<Boolean> EXEMPT = new ThreadLocal<>();

    private TenantWriteGuard() {}

    /**
     * 在不受租户状态限制的情况下执行任务，供服务声明例外，例如为已停用租户记录审计数据、冷静期结束后清理租户数据。
     *
     * <p>只对当前线程生效，可以嵌套；提交到其他线程的任务需要在该线程中再次声明。
     *
     * @param task 要执行的任务
     */
    public static void exempt(Runnable task) {
        Objects.requireNonNull(task, "task");
        Boolean previous = EXEMPT.get();
        EXEMPT.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            if (previous == null) {
                EXEMPT.remove();
            } else {
                EXEMPT.set(previous);
            }
        }
    }

    static boolean exempted() {
        return EXEMPT.get() != null;
    }
}
