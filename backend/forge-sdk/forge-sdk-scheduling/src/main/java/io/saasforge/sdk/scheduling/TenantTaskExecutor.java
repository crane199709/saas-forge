package io.saasforge.sdk.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.tenant.TenantStatusLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TenantTaskExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(TenantTaskExecutor.class);
    private final TenantDirectoryClient directory;
    private final TenantStatusLookup statuses;
    private final MeterRegistry metrics;

    TenantTaskExecutor(TenantDirectoryClient directory, TenantStatusLookup statuses, MeterRegistry metrics) {
        this.directory = directory;
        this.statuses = statuses;
        this.metrics = metrics;
    }

    void execute(String name, Runnable invocation, TaskRunOutcome outcome) {
        for (var tenant : directory.tenants()) {
            CurrentTenant.run(tenant, () -> {
                try {
                    String status = statuses.status(tenant);
                    if (!"trial".equals(status) && !"active".equals(status)) return;
                    invocation.run();
                    outcome.succeeded();
                    metrics.counter("forge.task.tenant.successes", "name", name).increment();
                } catch (Exception error) {
                    outcome.tenantFailed(tenant, error);
                    metrics.counter("forge.task.tenant.failures", "name", name).increment();
                    // 在租户绑定释放前记录，确保日志 MDC 保留失败租户。
                    LOG.error("Tenant Task {} failed for tenant {}", name, tenant, error);
                }
            });
        }
    }
}
