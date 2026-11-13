package io.saasforge.sdk.mq;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 基础设施表的每日维护；单条条件删除可跨实例幂等执行，不给仅用 MQ 的服务增加 Redis 依赖。 */
final class DeadEventMaintenance implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DeadEventMaintenance.class);
    private final DeadEventOperations events;
    private ScheduledExecutorService executor;

    DeadEventMaintenance(DeadEventOperations events) {
        this.events = events;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "forge-dead-event-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        events.purgeResolved();
                    } catch (RuntimeException error) {
                        log.warn("Failed to clean resolved dead events", error);
                    }
                },
                0,
                1,
                TimeUnit.DAYS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
