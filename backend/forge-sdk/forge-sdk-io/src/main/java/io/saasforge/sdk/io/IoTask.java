package io.saasforge.sdk.io;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 提交人可见的任务投影，失败文案可直接展示，结果对象 ID 不暴露。 */
public record IoTask(
        UUID id,
        String type,
        String status,
        UUID membershipId,
        long rowCount,
        String errorCode,
        String failureReason,
        @JsonIgnore UUID resultObjectId,
        OffsetDateTime heartbeatAt,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {
    /** 结果文件存在时，前端才显示下载入口；下载票据仍由服务端校验有效期。 */
    @JsonProperty("resultAvailable")
    public boolean resultAvailable() {
        return resultObjectId != null;
    }
}
