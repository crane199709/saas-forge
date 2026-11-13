package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 当前租户的一条覆盖前快照；旧值仅供服务内的数据修复使用，不应直接经 HTTP 暴露。 */
public record ImportBackup(
        UUID id,
        UUID taskId,
        String recordId,
        Map<String, Object> values,
        String importedVersion,
        OffsetDateTime expiresAt) {}
