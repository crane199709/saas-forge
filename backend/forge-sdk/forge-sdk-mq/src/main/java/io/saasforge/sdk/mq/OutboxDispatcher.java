package io.saasforge.sdk.mq;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把 Outbox 中到期的事件发送到 RocketMQ，发送成功后标记，失败则按退避推迟重试。
 *
 * <p>事务提交后由 {@link #wakeUp()} 即时触发，另按固定间隔扫描兜底（重试、其他实例写入、进程重启前未发出的事件）。
 * 所有投递在单个线程中串行执行。发送成功但标记前进程退出时事件会被重发，由消费端按事件 ID 去重。
 */
final class OutboxDispatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxMapper mapper;
    private final TransactionTemplate transaction;
    private final RocketMqSender sender;
    private final MqProperties properties;
    // 多次提交只需一轮投递：已有一轮排队时不再追加
    private final AtomicBoolean wakeUpPending = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    OutboxDispatcher(
            OutboxMapper mapper, TransactionTemplate transaction, RocketMqSender sender, MqProperties properties) {
        this.mapper = mapper;
        this.transaction = transaction;
        this.sender = sender;
        this.properties = properties;
    }

    /** 立即安排一轮投递，供发布事件的事务提交后调用。 */
    void wakeUp() {
        ScheduledExecutorService current = executor;
        if (current != null && wakeUpPending.compareAndSet(false, true)) {
            current.execute(() -> {
                wakeUpPending.set(false);
                dispatch();
            });
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "forge-outbox-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::dispatch, 0, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = executor;
        executor = null;
        current.shutdown();
        try {
            if (!current.awaitTermination(properties.requestTimeout().toMillis() * 2, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            sender.close();
        } catch (IOException e) {
            log.warn("Failed to close RocketMQ producer", e);
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** 逐批投递，直到没有到期事件或出现发送失败；异常不能逃出，否则定时任务会被取消。 */
    private void dispatch() {
        try {
            Boolean more;
            do {
                more = transaction.execute(status -> dispatchBatch());
            } while (Boolean.TRUE.equals(more));
        } catch (RuntimeException e) {
            log.error("Outbox dispatch failed", e);
        }
    }

    /**
     * 在持有行锁的事务内发送一批事件。
     *
     * @return 本批取满且全部发送成功，可能还有更多到期事件时为 true
     */
    private boolean dispatchBatch() {
        List<OutboxEntry> due = mapper.lockDue(properties.batchSize());
        for (OutboxEntry entry : due) {
            try {
                sender.send(entry);
            } catch (Exception e) {
                // MQ 多半整体不可用：本批其余事件留待下一轮，避免每条都等一次发送超时
                long backoff = properties.backoff(entry.attempts()).toMillis();
                mapper.markFailed(entry.id(), backoff, truncate(String.valueOf(e)));
                log.warn(
                        "Failed to send outbox event {} to topic {} (attempt {}), retrying in {} ms",
                        entry.id(),
                        entry.topic(),
                        entry.attempts() + 1,
                        backoff,
                        e);
                return false;
            }
            mapper.markSent(entry.id());
        }
        return due.size() == properties.batchSize();
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
