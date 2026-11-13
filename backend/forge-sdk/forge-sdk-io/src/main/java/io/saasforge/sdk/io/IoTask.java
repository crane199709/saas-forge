package io.saasforge.sdk.io;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 提交人可见的任务投影，失败文案可直接展示，结果对象 ID 不暴露。 */
public record IoTask(
        UUID id,
        String type,
        String importModel,
        String status,
        UUID membershipId,
        long rowCount,
        String errorCode,
        String failureReason,
        @JsonIgnore UUID resultObjectId,
        OffsetDateTime heartbeatAt,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        long snapshotCount,
        OffsetDateTime snapshotExpiresAt,
        String rollbackStatus,
        long rollbackAttempts,
        long restoredCount,
        long skippedCount,
        long failedCount) {
    /** 只有成功完成且仍在保留期内的覆盖快照可用于回滚。 */
    @JsonProperty("snapshotAvailable")
    public boolean snapshotAvailable() {
        return "SUCCEEDED".equals(status)
                && snapshotCount > restoredCount
                && snapshotExpiresAt != null
                && snapshotExpiresAt.isAfter(OffsetDateTime.now());
    }

    /** 结果文件存在时，前端才显示下载入口；下载票据仍由服务端校验有效期。 */
    @JsonProperty("resultAvailable")
    public boolean resultAvailable() {
        return resultObjectId != null;
    }
}
