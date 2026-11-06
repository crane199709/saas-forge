package io.saasforge.sdk.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

final class TaskRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);
    private final RedisTaskLock locks;
    private final MeterRegistry metrics;
    private final ExecutorService workers =
            Executors.newCachedThreadPool(new CustomizableThreadFactory("forge-task-worker-"));

    private final ScheduledExecutorService renewals =
            Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("forge-task-renew-"));

    TaskRunner(RedisTaskLock locks, MeterRegistry metrics) {
        this.locks = locks;
        this.metrics = metrics;
    }

    void trigger(String name, Runnable invocation) {
        String token = UUID.randomUUID().toString();
        try {
            if (!locks.acquire(name, token)) {
                metrics.counter("forge.task.skipped", "name", name).increment();
                return;
            }
        } catch (RuntimeException error) {
            metrics.counter("forge.task.lock.failures", "name", name).increment();
            LOG.warn("Task {} skipped: Redis lock unavailable", name, error);
            return;
        }
        try {
            workers.execute(() -> execute(name, token, invocation));
        } catch (RuntimeException error) {
            release(name, token);
            throw error;
        }
    }

    private void execute(String name, String token, Runnable invocation) {
        long started = System.nanoTime();
        metrics.counter("forge.task.executions", "name", name).increment();
        LOG.info("Task {} started", name);
        ScheduledFuture<?> renewal = null;
        try {
            renewal = renewals.scheduleAtFixedRate(() -> renew(name, token), 10, 10, TimeUnit.SECONDS);
            invocation.run();
        } catch (Exception error) {
            LOG.error("Task {} failed", name, error);
        } finally {
            if (renewal != null) {
                renewal.cancel(false);
            }
            long elapsed = System.nanoTime() - started;
            Timer.builder("forge.task.duration")
                    .tag("name", name)
                    .register(metrics)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            LOG.info("Task {} ended in {} ms", name, TimeUnit.NANOSECONDS.toMillis(elapsed));
            release(name, token);
        }
    }

    private void renew(String name, String token) {
        try {
            if (!locks.renew(name, token)) {
                LOG.warn("Task {} no longer owns its Redis lock", name);
            }
        } catch (RuntimeException error) {
            LOG.warn("Task {} could not renew Redis lock", name, error);
        }
    }

    private void release(String name, String token) {
        try {
            locks.release(name, token);
        } catch (RuntimeException error) {
            LOG.warn("Task {} could not release Redis lock; lease will expire", name, error);
        }
    }

    @Override
    public void close() {
        workers.shutdown();
        try {
            // 先让完成中的任务释放锁；立即中断会使 Lettuce 拒绝执行释放命令。
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException error) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            renewals.shutdownNow();
        }
    }
}
