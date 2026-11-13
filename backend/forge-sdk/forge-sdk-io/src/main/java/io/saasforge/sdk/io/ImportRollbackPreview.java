package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 只读回滚报告；blocked 为 true 时默认阻断，预览不授权或执行任何业务写入。 */
public record ImportRollbackPreview(
        UUID taskId,
        boolean available,
        ImportRollbackReason unavailableReason,
        boolean blocked,
        long totalCount,
        long rollbackableCount,
        long conflictCount,
        OffsetDateTime snapshotExpiresAt,
        List<ImportRollbackConflict> conflicts) {}
