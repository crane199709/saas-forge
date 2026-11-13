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
    private final ImportBackupMapper backups;
    private final AuditRecorder audit;
    private final IoTaskMapper mapper;
    private final ObjectStorage storage;
    private final IoProperties properties;
    private final StringRedisTemplate redis;
    private final IoTasks tasks;
    private final TransactionTemplate transaction;

    IoMaintenance(
            ImportBackupMapper backups,
            AuditRecorder audit,
            IoTaskMapper mapper,
            ObjectStorage storage,
            IoProperties properties,
            StringRedisTemplate redis,
            IoTasks tasks,
            TransactionTemplate transaction) {
        this.backups = backups;
        this.audit = audit;
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
        this.redis = redis;
        this.tasks = tasks;
        this.transaction = transaction;
    }
    /** 收尾中断执行与超过 24 小时的待确认导入；租户已停用时仍清理源文件。 */
    @GlobalTask(name = "forge-io-interrupted", fixedDelay = "30s")
    public void interrupt() {
        try (var tenants = redis.opsForSet()
                .scan(tasks.tenantKey(), ScanOptions.scanOptions().count(100).build())) {
            tenants.forEachRemaining(tenant -> CurrentTenant.run(UUID.fromString(tenant), () -> {
                try {
                    TenantWriteGuard.exempt(() -> {
                        interruptTenant();
                        cleanSources();
                        expireSnapshots();
                    });
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
            mapper.expireConfirmations(OffsetDateTime.now().minusHours(24));
            for (var task : stale) {
                if (task.sourceObjectId() != null) {
                    delete(task.sourceObjectId());
                    mapper.clearSource(task.id());
                }
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

    private void cleanSources() {
        for (var task : mapper.completedSources()) {
            transaction.executeWithoutResult(status -> {
                delete(task.sourceObjectId());
                mapper.clearSource(task.id());
            });
        }
    }
    /** 清空到期快照旧值并删除过期结果，保留任务历史；按任务分批处理。 */
    @TenantTask(name = "forge-io-expired-results", fixedDelay = "1h")
    public void expire() {
        expireSnapshots();
        var cutoff = OffsetDateTime.now().minus(properties.retention());
        while (true) {
            var expired = mapper.expired(cutoff);
            if (expired.isEmpty()) {
                return;
            }
            for (var task : expired) {
                transaction.executeWithoutResult(status -> {
                    delete(task.resultObjectId());
                    mapper.clearResult(task.id());
                });
            }
        }
    }

    private void expireSnapshots() {
        while (true) {
            var expiredTasks = backups.expiredTasks();
            if (expiredTasks.isEmpty()) {
                break;
            }
            for (var taskId : expiredTasks) {
                transaction.executeWithoutResult(status -> {
                    // 执行持有任务锁直至汇总提交，清理不能在恢复成功与计数之间移除快照。
                    backups.lockExpiryTask(taskId);
                    backups.preserveRollbackResults(taskId);
                    backups.expire(taskId);
                });
            }
        }
    }

    private void delete(UUID id) {
        try {
            storage.delete(id);
        } catch (ForgeException error) {
            if (!"storage.objectNotFound".equals(error.errorCode().code())) {
                throw error;
            }
        }
    }
}
