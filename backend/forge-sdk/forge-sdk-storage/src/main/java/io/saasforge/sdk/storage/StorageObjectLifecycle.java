package io.saasforge.sdk.storage;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 文件生命周期元数据，不包含对象 key 或文件内容。
 *
 * @param id 对象 ID
 * @param status 生命周期状态
 * @param retentionExpiresAt 保留截止时间；null 表示未声明保留期
 * @param expiredAt 实际失效时间；尚未失效时为 null
 * @param purgeAfter 最早可物理删除的时间；尚未失效时为 null，届时仍须检查业务引用
 */
public record StorageObjectLifecycle(
        UUID id,
        StorageObjectStatus status,
        OffsetDateTime retentionExpiresAt,
        OffsetDateTime expiredAt,
        OffsetDateTime purgeAfter) {
    /** 失效后固定保留七天，期间对象仍占用容量。 */
    public static final Duration PURGE_GRACE_PERIOD = Duration.ofDays(7);
}
