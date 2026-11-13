package io.saasforge.sdk.manifest;

import io.saasforge.sdk.core.ResourceManifest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 本实例有限、异步的上报生命周期；不等待 IAM 恢复才完成应用启动。 */
public final class ManifestReporting implements AutoCloseable {
    private final boolean enabled;
    private final ManifestReporter reporter;
    private final Duration retryDelay;
    private final Duration backgroundDelay;
    private final int backgroundLimit;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "forge-manifest-report");
        thread.setDaemon(true);
        return thread;
    });
    private volatile OnboardingStatus status;
    private boolean closed;
    /** 使用初次 + 3 次退避重试，之后每五分钟最多补报十二次。 */
    public ManifestReporting(boolean enabled, ManifestReporter reporter) {
        this(enabled, reporter, Duration.ofSeconds(1), Duration.ofMinutes(5), 12);
    }
    // 时间是测试边界；生产只使用固定的有限策略。
    ManifestReporting(
            boolean enabled,
            ManifestReporter reporter,
            Duration retryDelay,
            Duration backgroundDelay,
            int backgroundLimit) {
        this.enabled = enabled;
        this.reporter = reporter;
        this.retryDelay = retryDelay;
        this.backgroundDelay = backgroundDelay;
        this.backgroundLimit = backgroundLimit;
        status = new OnboardingStatus(enabled, null, null, null, null, 0, enabled ? "NOT_STARTED" : "DISABLED");
    }
    /** 启动一次上报，重复的 Ready 事件不会生成第二套任务。 */
    public synchronized void start(ResourceManifest manifest) {
        if (!enabled || closed || !status.phase().equals("NOT_STARTED")) {
            return;
        }
        update("REPORTING");
        executor.execute(() -> attempt(manifest));
    }
    /** 返回当前不可变快照。 */
    public OnboardingStatus status() {
        return status;
    }

    private void attempt(ResourceManifest manifest) {
        synchronized (this) {
            if (closed) {
                return;
            }
            status = new OnboardingStatus(
                    enabled,
                    status.reportedVersion(),
                    status.lastSuccessAt(),
                    status.lastFailure(),
                    status.lastFailureAt(),
                    status.attempts() + 1,
                    status.phase());
        }
        ManifestFailure failure = null;
        try {
            reporter.report(manifest);
        } catch (ManifestReportException error) {
            failure = error.reason();
        } catch (RuntimeException error) {
            failure = ManifestFailure.UNEXPECTED_RESPONSE;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            if (failure == null) {
                status = new OnboardingStatus(
                        enabled,
                        manifest.version(),
                        Instant.now(),
                        status.lastFailure(),
                        status.lastFailureAt(),
                        status.attempts(),
                        "REPORTED");
                executor.shutdown();
                return;
            }
            int attempts = status.attempts();
            String phase = attempts < 4 ? "REPORTING" : attempts < 4 + backgroundLimit ? "BACKGROUND" : "EXHAUSTED";
            status = new OnboardingStatus(
                    enabled, status.reportedVersion(), status.lastSuccessAt(), failure, Instant.now(), attempts, phase);
            if (phase.equals("EXHAUSTED")) {
                executor.shutdown();
                return;
            }
            long delay = attempts < 4 ? retryDelay.toMillis() * (1L << (attempts - 1)) : backgroundDelay.toMillis();
            executor.schedule(() -> attempt(manifest), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void update(String phase) {
        status = new OnboardingStatus(
                enabled,
                status.reportedVersion(),
                status.lastSuccessAt(),
                status.lastFailure(),
                status.lastFailureAt(),
                status.attempts(),
                phase);
    }
    /** 取消等待中的补报并中断正在进行的 HTTP 请求。 */
    @Override
    public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        if (enabled && !status.phase().equals("REPORTED") && !status.phase().equals("EXHAUSTED")) {
            update("STOPPED");
        }
    }
}
