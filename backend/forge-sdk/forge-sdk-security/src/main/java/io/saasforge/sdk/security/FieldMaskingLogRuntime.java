package io.saasforge.sdk.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 日志专用的短期快照；永不在业务或 Logback 线程访问 IAM，过期立即丢弃原值。 */
final class FieldMaskingLogRuntime implements AutoCloseable {
    static final String REDACTED = "[redacted]";
    private static volatile Map<String, String> declarations = Map.of();
    private static volatile FieldMaskingLogRuntime current;
    private final FieldMaskingPolicyProvider provider;
    private final MaskingEngine engine = new MaskingEngine();
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            task -> {
                var thread = new Thread(task, "forge-field-masking-log");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    FieldMaskingLogRuntime(FieldMaskingPolicyProvider provider) {
        this.provider = provider;
        current = this;
    }

    static void declarations(Map<String, String> names) {
        declarations = Map.copyOf(names);
    }

    static String path(String name) {
        return declarations.get(name);
    }

    static String mask(String path, Object value, Map<String, String> mdc) {
        try {
            var runtime = current;
            if (runtime == null || path == null || path.isEmpty()) {
                return REDACTED;
            }
            UUID tenant = UUID.fromString(mdc.get("tenant_id"));
            Snapshot snapshot = runtime.snapshots.get(tenant);
            if (snapshot == null || System.nanoTime() - snapshot.requestedAt() > TimeUnit.SECONDS.toNanos(1)) {
                runtime.refresh(tenant);
                return REDACTED;
            }
            var field = snapshot.policies().get(path);
            if (field == null || field.rule() == null) {
                return REDACTED;
            }
            return runtime.engine.apply(field.rule(), value == null ? null : String.valueOf(value));
        } catch (RuntimeException error) {
            return REDACTED;
        }
    }

    private void refresh(UUID tenant) {
        if (!loading.add(tenant)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    // 有效期从请求开始计算，慢响应不能把变更前的快照重新延长一秒。
                    long requestedAt = System.nanoTime();
                    var policies = Map.copyOf(provider.policies(tenant));
                    if (!worker.isShutdown()) {
                        snapshots.put(tenant, new Snapshot(policies, requestedAt));
                    }
                } catch (RuntimeException error) {
                    snapshots.remove(tenant);
                } finally {
                    loading.remove(tenant);
                }
            });
        } catch (RejectedExecutionException error) {
            loading.remove(tenant);
        }
    }

    @Override
    public void close() {
        if (current == this) {
            current = null;
            // 只保留路径字符串供停机阶段继续失败关闭，不持有应用、类加载器或提供方。
        }
        worker.shutdownNow();
        snapshots.clear();
        loading.clear();
    }

    private record Snapshot(Map<String, EffectiveFieldPolicy> policies, long requestedAt) {}
}
