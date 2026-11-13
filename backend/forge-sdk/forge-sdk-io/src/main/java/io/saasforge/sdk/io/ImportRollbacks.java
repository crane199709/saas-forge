package io.saasforge.sdk.io;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** 覆盖导入的安全预览与逐记录回滚；业务服务负责公开接口的独立回滚权限与当前数据权限。 */
public final class ImportRollbacks {
    private final IoTasks tasks;
    private final ImportBackups backups;
    private final ImportBackupMapper mapper;

    private final TransactionTemplate transaction;
    private final AuditRecorder audit;

    ImportRollbacks(
            IoTasks tasks,
            ImportBackups backups,
            ImportBackupMapper mapper,
            TransactionTemplate transaction,
            AuditRecorder audit) {
        this.tasks = tasks;
        this.backups = backups;
        this.mapper = mapper;
        this.transaction = transaction;
        this.audit = audit;
    }

    /**
     * 预览当前提交人的覆盖导入，沿用任务查询的租户和提交人边界。
     * 修改标记读取器必须沿用当前成员的数据权限，不能使用提交导入时的旧权限快照。
     */
    public ImportRollbackPreview preview(UUID taskId, Class<?> model, ImportVersionReader reader) {
        Objects.requireNonNull(reader, "A current data-permission-aware version reader is required");
        IoTask task = tasks.get(taskId);
        ImportRollbackReason unavailable = unavailable(task, model);
        if (unavailable != null) {
            return new ImportRollbackPreview(
                    taskId, false, unavailable, true, task.snapshotCount(), 0, 0, task.snapshotExpiresAt(), List.of());
        }
        var conflicts = new ArrayList<ImportRollbackConflict>();
        long count = 0;
        long restored = 0;
        for (long offset = 0; ; offset += 1000) {
            var page = backups.list(taskId, 1000, offset);
            for (var backup : page) {
                count++;
                var state = mapper.state(taskId, backup.recordId());
                if (state != null && "RESTORED".equals(state.rollbackStatus())) {
                    restored++;
                    continue;
                }
                var conflict = check(backup, reader);
                if (conflict != null) {
                    conflicts.add(conflict);
                }
            }
            if (page.size() < 1000) {
                break;
            }
        }
        // 长报告生成期间也可能跨过保留截止时间，不把部分过期伪装成可用报告。
        if (!task.snapshotExpiresAt().isAfter(OffsetDateTime.now())) {
            return new ImportRollbackPreview(
                    taskId,
                    false,
                    ImportRollbackReason.SNAPSHOT_EXPIRED,
                    true,
                    task.snapshotCount(),
                    0,
                    0,
                    task.snapshotExpiresAt(),
                    List.of());
        }
        if (count != task.snapshotCount()) {
            return new ImportRollbackPreview(
                    taskId,
                    false,
                    ImportRollbackReason.SNAPSHOT_UNAVAILABLE,
                    true,
                    task.snapshotCount(),
                    0,
                    0,
                    task.snapshotExpiresAt(),
                    List.of());
        }
        return new ImportRollbackPreview(
                taskId,
                true,
                null,
                !conflicts.isEmpty(),
                count,
                count - restored - conflicts.size(),
                conflicts.size(),
                task.snapshotExpiresAt(),
                List.copyOf(conflicts));
    }

    /**
     * 重新检查单条快照，返回 null 表示此刻未发现冲突。读取最新快照状态，不信任预览或传入对象中的旧状态。
     * 执行方须先在事务内按当前数据权限锁定业务行，再调用此方法，且在同一事务持锁期间完成条件恢复；
     * 修改标记读取器与恢复写入都不能绕过当前数据权限。此检查本身只读，不能作为跨事务的执行许可。
     */
    public ImportRollbackConflict check(ImportBackup backup, ImportVersionReader reader) {
        var state = mapper.state(backup.taskId(), backup.recordId());
        ImportRollbackReason reason;
        if (state == null) {
            reason = ImportRollbackReason.SNAPSHOT_UNAVAILABLE;
        } else if (!state.expiresAt().isAfter(OffsetDateTime.now())) {
            reason = ImportRollbackReason.SNAPSHOT_EXPIRED;
        } else {
            String current = reader.readVersion(backup.recordId());
            if (current == null) {
                reason = ImportRollbackReason.RECORD_UNAVAILABLE;
            } else if (state.supersededByTaskId() != null) {
                reason = ImportRollbackReason.LATER_IMPORT;
            } else if (state.importedVersion() == null) {
                reason = ImportRollbackReason.VERSION_UNAVAILABLE;
            } else {
                reason = state.importedVersion().equals(current) ? null : ImportRollbackReason.RECORD_MODIFIED;
            }
        }
        return reason == null ? null : new ImportRollbackConflict(backup.recordId(), reason);
    }

    /**
     * 执行本人任务的回滚，默认在当前冲突报告阻断时一条不恢复；显式 skipConflicts 才跳过冲突。
     * 每条记录独立事务，业务恢复与 RESTORED 标记共同提交，并在该事务提交后记录实际处理数的审计。
     * 失败和中断后可重试，已恢复记录不再读取业务版本或写入；拒绝或无操作的调用另记零计数审计。
     * 业务回调禁止改动快照以外字段。公开 HTTP 接口仍须检查独立回滚权限。
     */
    public ImportRollbackResult execute(
            UUID taskId, Class<?> model, boolean skipConflicts, ImportRollbackWriter writer) {
        Objects.requireNonNull(writer, "A current data-permission-aware rollback writer is required");
        tasks.get(taskId);
        return transaction.execute(status -> {
            if (mapper.lockTask(taskId, TenantContext.current().membershipId()) == null) {
                throw new ForgeException(IoErrorCode.NOT_FOUND);
            }
            var preview = preview(taskId, model, writer);
            long restored = 0;
            long skipped = 0;
            long failed = 0;
            var conflicts = new ArrayList<ImportRollbackConflict>();
            String outcome;
            if (!preview.available()) {
                outcome = "UNAVAILABLE";
            } else if (preview.blocked() && !skipConflicts) {
                outcome = "BLOCKED";
                conflicts.addAll(preview.conflicts());
            } else {
                records:
                for (long offset = 0; ; offset += 1000) {
                    var page = backups.list(taskId, 1000, offset);
                    for (var backup : page) {
                        var state = mapper.state(taskId, backup.recordId());
                        if (state != null && "RESTORED".equals(state.rollbackStatus())) {
                            continue;
                        }
                        try {
                            var conflict = transaction.execute(recordStatus -> {
                                // 与覆盖导入保持业务行 -> 快照的锁顺序；重查使用持锁后读取的实际版本。
                                String version = writer.lockAndReadVersion(backup.recordId());
                                mapper.lockState(taskId, backup.recordId());
                                var checked = check(backup, id -> version);
                                if (checked == null) {
                                    writer.restore(backup);
                                    if (mapper.rollbackRecord(
                                                    backup.id(),
                                                    "RESTORED",
                                                    TenantContext.current().identityId())
                                            != 1) {
                                        throw new IllegalStateException("Rollback success marker was not persisted");
                                    }
                                    recordAudit(taskId, 1, 0, 0, null);
                                } else if (skipConflicts) {
                                    mapper.rollbackRecord(
                                            backup.id(),
                                            "SKIPPED",
                                            TenantContext.current().identityId());
                                    recordAudit(taskId, 0, 1, 0, IoErrorCode.ROLLBACK_CONFLICTS);
                                }
                                return checked;
                            });
                            if (conflict == null) {
                                restored++;
                            } else {
                                conflicts.add(conflict);
                                if (!skipConflicts) {
                                    break records;
                                }
                                skipped++;
                            }
                        } catch (RuntimeException error) {
                            transaction.executeWithoutResult(recordStatus -> {
                                mapper.rollbackRecord(
                                        backup.id(),
                                        "FAILED",
                                        TenantContext.current().identityId());
                                recordAudit(taskId, 0, 0, 1, IoErrorCode.FAILED);
                            });
                            failed++;
                        }
                    }
                    if (page.size() < 1000) {
                        break;
                    }
                }
                outcome = !skipConflicts && !conflicts.isEmpty()
                        ? "BLOCKED"
                        : (failed == 0 && skipped == 0 ? "SUCCEEDED" : "PARTIAL");
            }
            mapper.rollbackTask(taskId, outcome, TenantContext.current().identityId());
            var task = tasks.get(taskId);
            // 已提交记录各自审计，外层回滚不会漏掉它们；整轮只补拒绝/无操作事件，避免重复计数。
            if ("UNAVAILABLE".equals(outcome) || "BLOCKED".equals(outcome) || restored + skipped + failed == 0) {
                recordAudit(
                        taskId,
                        0,
                        0,
                        0,
                        "UNAVAILABLE".equals(outcome)
                                ? IoErrorCode.ROLLBACK_UNAVAILABLE
                                : ("BLOCKED".equals(outcome) ? IoErrorCode.ROLLBACK_CONFLICTS : null));
            }
            return new ImportRollbackResult(
                    taskId,
                    outcome,
                    preview.unavailableReason(),
                    task.restoredCount(),
                    task.skippedCount(),
                    task.failedCount(),
                    List.copyOf(conflicts));
        });
    }

    private void recordAudit(UUID taskId, long restored, long skipped, long failed, IoErrorCode error) {
        var event = AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, "io.importRollback")
                .tenant(CurrentTenant.require())
                .actor(TenantContext.current().identityId())
                .resource("ioTask", taskId)
                .detail("taskId", taskId.toString())
                .detail("restoredCount", restored)
                .detail("skippedCount", skipped)
                .detail("failedCount", failed);
        if (error != null) {
            event.failure(error.code());
        }
        audit.record(event.build());
    }

    private static ImportRollbackReason unavailable(IoTask task, Class<?> model) {
        if (!"IMPORT".equals(task.type()) || !"SUCCEEDED".equals(task.status())) {
            return ImportRollbackReason.TASK_NOT_SUCCEEDED;
        }
        if (!model.getName().equals(task.importModel())) {
            return ImportRollbackReason.MODEL_MISMATCH;
        }
        if (task.snapshotCount() == 0 || task.snapshotExpiresAt() == null) {
            return ImportRollbackReason.NO_SNAPSHOTS;
        }
        if (!task.snapshotExpiresAt().isAfter(OffsetDateTime.now())) {
            return ImportRollbackReason.SNAPSHOT_EXPIRED;
        }
        return null;
    }
}
