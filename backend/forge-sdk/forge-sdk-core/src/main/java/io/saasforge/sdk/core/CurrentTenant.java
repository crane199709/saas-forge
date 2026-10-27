package io.saasforge.sdk.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * 当前线程的 Active Tenant，是 SDK 租户隔离（SQL 注入、RLS 会话变量、Redis 前缀）的唯一来源。
 *
 * <p>刻意不用 {@link InheritableThreadLocal}：线程池会复用线程，继承的租户可能串到其他任务。
 * 异步线程与定时任务必须经 {@link #wrap(Runnable)} 携带或经 {@link #run(UUID, Runnable)} 声明租户，
 * 否则访问租户数据时显式失败，而不是以无租户身份执行。
 *
 * <p>绑定的同时写入日志 MDC 的 {@value #MDC_KEY}：请求、消息消费与定时任务都经这里绑定租户，日志因此无需业务代码参与即带上租户。
 */
public final class CurrentTenant {
    /** 日志 MDC 中租户 ID 的键。 */
    public static final String MDC_KEY = "tenant_id";

    private static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();

    private CurrentTenant() {}

    /**
     * 返回当前线程绑定的租户。
     *
     * @return 未绑定时为空，例如健康检查与只访问全局表的任务
     */
    public static Optional<UUID> find() {
        return Optional.ofNullable(TENANT.get());
    }

    /**
     * 返回当前线程绑定的租户。
     *
     * @return 当前租户 ID
     * @throws IllegalStateException 未绑定租户上下文时
     */
    public static UUID require() {
        UUID tenantId = TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context bound to thread "
                    + Thread.currentThread().getName()
                    + "; carry it with CurrentTenant.wrap or declare it with CurrentTenant.run");
        }
        return tenantId;
    }

    /**
     * 在当前线程绑定租户，关闭返回值时恢复绑定前的状态。
     *
     * @param tenantId 要绑定的租户 ID
     * @return 用于 try-with-resources 的绑定句柄
     */
    public static Binding bind(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        UUID previous = TENANT.get();
        TENANT.set(tenantId);
        MDC.put(MDC_KEY, tenantId.toString());
        return new Binding(previous);
    }

    /**
     * 以指定租户执行任务，供定时任务等没有请求上下文的场景声明租户。
     *
     * @param tenantId 任务所属租户
     * @param task 要执行的任务
     */
    public static void run(UUID tenantId, Runnable task) {
        try (Binding ignored = bind(tenantId)) {
            task.run();
        }
    }

    /**
     * 让任务在其他线程中沿用提交时的租户。
     *
     * @param task 要提交到其他线程的任务
     * @return 提交时已绑定租户则返回携带该租户的任务，否则原样返回，执行时访问租户数据会失败
     */
    public static Runnable wrap(Runnable task) {
        UUID tenantId = TENANT.get();
        return tenantId == null ? task : () -> run(tenantId, task);
    }

    /** {@link #bind(UUID)} 的句柄，关闭时恢复外层绑定，允许嵌套使用。 */
    public static final class Binding implements AutoCloseable {
        private final UUID previous;

        private Binding(UUID previous) {
            this.previous = previous;
        }

        /** 恢复绑定前的租户；外层未绑定时清除，避免线程复用时残留。 */
        @Override
        public void close() {
            if (previous == null) {
                TENANT.remove();
                MDC.remove(MDC_KEY);
            } else {
                TENANT.set(previous);
                MDC.put(MDC_KEY, previous.toString());
            }
        }
    }
}
