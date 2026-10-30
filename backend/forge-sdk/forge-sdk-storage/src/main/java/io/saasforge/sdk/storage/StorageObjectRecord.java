package io.saasforge.sdk.storage;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code forge_storage_object} 的一行。
 *
 * @param status {@link #PENDING} 或 {@link #STORED}
 * @param storedAt 确认上传的时间，未确认时为 null
 */
record StorageObjectRecord(
        UUID id,
        String objectKey,
        String filename,
        String contentType,
        long size,
        String status,
        OffsetDateTime uploadExpiresAt,
        OffsetDateTime storedAt) {
    static final String PENDING = "PENDING";
    static final String STORED = "STORED";

    boolean stored() {
        return STORED.equals(status);
    }

    StoredObject toStoredObject() {
        return new StoredObject(id, filename, contentType, size, storedAt);
    }
}
