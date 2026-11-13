package io.saasforge.sdk.io;

import java.util.List;
import java.util.UUID;

/** 回滚执行的累计结果，不包含快照字段原文；重试只处理尚未恢复的记录。 */
public record ImportRollbackResult(
        UUID taskId,
        String status,
        ImportRollbackReason unavailableReason,
        long restoredCount,
        long skippedCount,
        long failedCount,
        List<ImportRollbackConflict> conflicts) {}
