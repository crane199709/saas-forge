package io.saasforge.sdk.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.tenant.TenantStatusLookup;
import java.util.UUID;
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

    void requireEligible(UUID tenant) {
        String status = statuses.status(tenant);
        if (!"trial".equals(status) && !"active".equals(status))
            throw new ForgeException(TaskErrorCode.TENANT_NOT_ELIGIBLE);
    }

    void execute(String name, Runnable invocation, TaskRunOutcome outcome) {
        for (var tenant : directory.tenants()) execute(name, tenant, invocation, outcome, false);
    }

    void retry(String name, UUID tenant, Runnable invocation, TaskRunOutcome outcome) {
        execute(name, tenant, invocation, outcome, true);
    }

    private void execute(String name, UUID tenant, Runnable invocation, TaskRunOutcome outcome, boolean retry) {
        CurrentTenant.run(tenant, () -> {
            try {
                String status = statuses.status(tenant);
                if (!"trial".equals(status) && !"active".equals(status)) {
                    if (retry) throw new ForgeException(TaskErrorCode.TENANT_NOT_ELIGIBLE);
                    return;
                }
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
