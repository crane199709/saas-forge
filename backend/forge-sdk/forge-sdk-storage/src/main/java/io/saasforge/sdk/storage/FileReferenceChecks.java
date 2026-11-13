package io.saasforge.sdk.storage;

import io.saasforge.sdk.core.CurrentTenant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按用途批量核对业务引用，供文件失效与物理删除共用同一保护规则。
 *
 * <p>最多两个并发检查，不排队。检查器不响应中断时保留其线程，不再扩容；池满时跳过并告警。
 * 检查器在独立线程运行，不继承调用方事务和请求身份。
 */
public final class FileReferenceChecks implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FileReferenceChecks.class);
    private final List<FileReferenceChecker> checkers;
    private final StorageRetentionProperties properties;
    private final ThreadPoolExecutor executor;

    /** 创建检查入口；检查器由业务服务声明。 */
    public FileReferenceChecks(List<FileReferenceChecker> checkers, StorageRetentionProperties properties) {
        this.checkers = List.copyOf(checkers);
        this.properties = properties;
        var sequence = new AtomicInteger();
        // 零队列防止超时任务堆积；两个工作线程允许一个挂死用途之外的检查继续执行。
        this.executor = new ThreadPoolExecutor(0, 2, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "forge-file-reference-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 返回单批候选数上限，扫描方须据此分批。 */
    public int batchSize() {
        return properties.batchSize();
    }

    /**
     * 返回已确认无业务引用的候选；缺覆盖、异常、超时或非法返回都返回空集并告警。
     * 调用方须先按 ID 顺序持有整批对象行锁，再检查并在同一事务内处理返回的 ID；
     * 失效前与物理删除前必须分别检查，不能复用旧结果。业务绑定在可写事务内先调用
     * {@link ObjectStorage#get(StoragePurpose, UUID)} 再写入引用，与治理共用行锁。
     *
     * @param purpose 候选用途，同批不得混合用途
     * @param candidates 候选对象 ID，数量不超过 {@link #batchSize()}
     * @param phase 告警中的治理阶段，如 {@code expire} 或 {@code purge}
     */
    public Set<UUID> unreferenced(String purpose, List<UUID> candidates, String phase) {
        UUID tenant = CurrentTenant.require();
        List<UUID> batch = List.copyOf(candidates);
        if (batch.isEmpty()) {
            return Set.of();
        }
        if (batch.size() > batchSize()) {
            warn(tenant, purpose, phase, "batch-limit");
            return Set.of();
        }
        Future<Set<UUID>> future = null;
        try {
            future = executor.submit(() -> {
                try (var ignored = CurrentTenant.bind(tenant)) {
                    return check(purpose, batch, phase, tenant);
                }
            });
            return future.get(properties.referenceCheckTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            warn(tenant, purpose, phase, "timeout");
        } catch (RejectedExecutionException failure) {
            warn(tenant, purpose, phase, "capacity-exhausted");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            warn(tenant, purpose, phase, "interrupted");
        } catch (ExecutionException failure) {
            // 不记录异常消息或堆栈，检查器异常可能包含业务文件内容或凭证。
            warn(tenant, purpose, phase, "checker-failed");
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        return Set.of();
    }

    private Set<UUID> check(String purpose, List<UUID> candidates, String phase, UUID tenant) {
        var unreferenced = new HashSet<>(candidates);
        boolean covered = false;
        for (var checker : checkers) {
            if (checker.purposes().contains(purpose)) {
                covered = true;
                Set<UUID> referenced = checker.referencedObjects(purpose, candidates);
                if (referenced == null
                        || referenced.stream().anyMatch(Objects::isNull)
                        || !candidates.containsAll(referenced)) {
                    warn(tenant, purpose, phase, "invalid-result");
                    return Set.of();
                }
                unreferenced.removeAll(referenced);
            }
        }
        if (!covered) {
            warn(tenant, purpose, phase, "missing-checker");
            return Set.of();
        }
        return Set.copyOf(unreferenced);
    }

    private static void warn(UUID tenant, String purpose, String phase, String reason) {
        log.warn(
                "File reference check skipped tenant={} purpose={} phase={} reason={}", tenant, purpose, phase, reason);
    }

    /** 关闭线程池，取消仍在执行的检查；不等待不响应中断的业务实现。 */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
