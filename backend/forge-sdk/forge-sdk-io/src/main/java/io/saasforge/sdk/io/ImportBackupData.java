package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Mapper 的 JSON 文本投影，不作为服务对外模型。 */
record ImportBackupData(
        UUID id, UUID taskId, String recordId, String valuesJson, String importedVersion, OffsetDateTime expiresAt) {}
