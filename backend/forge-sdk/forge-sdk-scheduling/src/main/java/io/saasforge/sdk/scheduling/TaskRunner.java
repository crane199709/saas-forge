package io.saasforge.sdk.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

final class TaskRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);
    private final RedisTaskLock locks;
    private final MeterRegistry metrics;
    private final TaskRunHistory history;
    private final ExecutorService workers =
            Executors.newCachedThreadPool(new CustomizableThreadFactory("forge-task-worker-"));

    private final ScheduledExecutorService renewals =
            Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("forge-task-renew-"));

    TaskRunner(RedisTaskLock locks, MeterRegistry metrics, TaskRunHistory history) {
        this.locks = locks;
        this.metrics = metrics;
        this.history = history;
    }

    void trigger(String name, TaskRun.Type type, Consumer<TaskRunOutcome> invocation) {
        String token = UUID.randomUUID().toString();
        try {
            if (!locks.acquire(name, token)) {
                metrics.counter("forge.task.skipped", "name", name).increment();
                return;
            }
        } catch (RuntimeException error) {
            // 拿不到锁就无法判断其他实例是否在执行：与抢锁失败一样跳过本轮，不排队补跑，等下次触发。
            metrics.counter("forge.task.lock.failures", "name", name).increment();
            LOG.warn("Task {} skipped: Redis lock unavailable", name, error);
            return;
        }
        try {
            TaskRun run = history.start(name, type, token);
            workers.execute(() -> execute(run, token, invocation));
        } catch (RuntimeException error) {
            release(name, token);
            throw error;
        }
    }

    TaskTriggerResponse manual(
            String name, TaskRun.Type type, UUID operatorId, UUID targetTenantId, Consumer<TaskRunOutcome> invocation) {
        String token = UUID.randomUUID().toString();
        if (!locks.acquire(name, token)) throw new ForgeException(TaskErrorCode.ALREADY_RUNNING);
        try {
            TaskRun run = history.start(name, type, token, TaskRun.Trigger.MANUAL, operatorId, targetTenantId);
            workers.execute(() -> execute(run, token, invocation));
            return new TaskTriggerResponse(run.id());
        } catch (RuntimeException error) {
            release(name, token);
            throw error;
        }
    }

    private void execute(TaskRun run, String token, Consumer<TaskRunOutcome> invocation) {
        String name = run.name();
        long started = System.nanoTime();
        var outcome = new TaskRunOutcome();
        metrics.counter("forge.task.executions", "name", name).increment();
        LOG.info("Task {} started", name);
        ScheduledFuture<?> renewal = null;
        try {
            // 周期取 30 秒租约（RedisTaskLock.LEASE）的三分之一：错过一次续约，租约也不会到期。
            renewal = renewals.scheduleAtFixedRate(() -> renew(name, token), 10, 10, TimeUnit.SECONDS);
            invocation.accept(outcome);
        } catch (Exception error) {
            outcome.failed(error);
            LOG.error("Task {} failed", name, error);
        } catch (Error error) {
            outcome.failed(error);
            throw error;
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
            history.finish(outcome.complete(run), token);
            release(name, token);
        }
    }

    // 异常不能逃出：scheduleAtFixedRate 遇异常即取消后续续约，一次 Redis 抖动就会让长任务失去锁。
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
