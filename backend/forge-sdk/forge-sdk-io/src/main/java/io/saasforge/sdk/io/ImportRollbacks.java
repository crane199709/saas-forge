package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 覆盖导入的安全只读预览；业务服务负责公开接口的独立回滚权限与当前数据权限。 */
public final class ImportRollbacks {
    private final IoTasks tasks;
    private final ImportBackups backups;
    private final ImportBackupMapper mapper;

    ImportRollbacks(IoTasks tasks, ImportBackups backups, ImportBackupMapper mapper) {
        this.tasks = tasks;
        this.backups = backups;
        this.mapper = mapper;
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
        for (long offset = 0; ; offset += 1000) {
            var page = backups.list(taskId, 1000, offset);
            for (var backup : page) {
                count++;
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
                count - conflicts.size(),
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
