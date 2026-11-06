package io.saasforge.sdk.io;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.scheduling.GlobalTask;
import io.saasforge.sdk.scheduling.TenantTask;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.tenant.TenantWriteGuard;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 中断任务与过期文件的定时收尾；按租户恢复 RLS，不需要跨租户数据库权限。 */
public final class IoMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(IoMaintenance.class);
    private final AuditRecorder audit;
    private final IoTaskMapper mapper;
    private final ObjectStorage storage;
    private final IoProperties properties;
    private final StringRedisTemplate redis;
    private final IoTasks tasks;
    private final TransactionTemplate transaction;

    IoMaintenance(
            AuditRecorder audit,
            IoTaskMapper mapper,
            ObjectStorage storage,
            IoProperties properties,
            StringRedisTemplate redis,
            IoTasks tasks,
            TransactionTemplate transaction) {
        this.audit = audit;
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
        this.redis = redis;
        this.tasks = tasks;
        this.transaction = transaction;
    }
    /** 也收尾重启后遗留的等待任务；租户已停用时仍需标记失败。 */
    @GlobalTask(name = "forge-io-interrupted", fixedDelay = "30s")
    public void interrupt() {
        try (var tenants = redis.opsForSet()
                .scan(tasks.tenantKey(), ScanOptions.scanOptions().count(100).build())) {
            tenants.forEachRemaining(tenant -> CurrentTenant.run(UUID.fromString(tenant), () -> {
                try {
                    TenantWriteGuard.exempt(this::interruptTenant);
                } catch (RuntimeException error) {
                    LOG.warn("IO recovery failed for tenant {}", tenant, error);
                }
            }));
        }
    }

    void interruptTenant() {
        var cutoff = OffsetDateTime.now().minus(properties.heartbeatTimeout());
        transaction.executeWithoutResult(status -> {
            var stale = mapper.staleTasks(cutoff);
            mapper.interrupt(cutoff);
            for (var task : stale) {
                if (task.sourceObjectId() != null) delete(task.sourceObjectId());
                audit.record(AuditEvent.builder(
                                AuditRealm.TENANT,
                                AuditCategory.OPERATION,
                                "IMPORT".equals(task.type()) ? "import.run" : "export.run")
                        .tenant(CurrentTenant.require())
                        .actor(task.actorId())
                        .resource("ioTask", task.id())
                        .failure(IoErrorCode.INTERRUPTED.code())
                        .detail("rowCount", task.rowCount())
                        .detail("query", task.summary())
                        .build());
            }
        });
    }
    /** 删除过期结果，任务记录保留；分批处理避免把任务历史整体加载。 */
    @TenantTask(name = "forge-io-expired-results", fixedDelay = "1h")
    public void expire() {
        var cutoff = OffsetDateTime.now().minus(properties.retention());
        while (true) {
            var expired = mapper.expired(cutoff);
            if (expired.isEmpty()) return;
            for (var task : expired)
                transaction.executeWithoutResult(status -> {
                    delete(task.resultObjectId());
                    mapper.clearResult(task.id());
                });
        }
    }

    private void delete(UUID id) {
        try {
            storage.delete(id);
        } catch (ForgeException error) {
            if (!"storage.objectNotFound".equals(error.errorCode().code())) throw error;
        }
    }
}
