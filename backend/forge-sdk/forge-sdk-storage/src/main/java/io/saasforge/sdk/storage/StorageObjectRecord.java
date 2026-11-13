package io.saasforge.sdk.storage;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code forge_storage_object} 的一行。
 *
 * @param status 文件生命周期状态
 * @param storedAt 确认上传的时间，未确认时为 null
 */
record StorageObjectRecord(
        UUID id,
        String objectKey,
        String purpose,
        String filename,
        String contentType,
        long size,
        StorageObjectStatus status,
        OffsetDateTime uploadExpiresAt,
        OffsetDateTime storedAt,
        OffsetDateTime retentionExpiresAt,
        OffsetDateTime expiredAt) {

    boolean stored() {
        return status == StorageObjectStatus.STORED;
    }

    StorageObjectLifecycle lifecycle() {
        return new StorageObjectLifecycle(
                id,
                status,
                retentionExpiresAt,
                expiredAt,
                expiredAt == null ? null : expiredAt.plus(StorageObjectLifecycle.PURGE_GRACE_PERIOD));
    }

    StoredObject toStoredObject() {
        return new StoredObject(id, filename, contentType, size, storedAt);
    }
}
