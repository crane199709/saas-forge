package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 确认时从持久任务恢复源文件与提交人，不保留进程内回调或已转换行。 */
record IoConfirmation(
        UUID membershipId, String status, UUID sourceObjectId, OffsetDateTime heartbeatAt, String importModel) {}
