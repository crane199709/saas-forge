package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 重新检查时读取的最新快照状态，不包含字段旧值。 */
record ImportBackupState(
        String importedVersion, OffsetDateTime expiresAt, UUID supersededByTaskId, String rollbackStatus) {}
