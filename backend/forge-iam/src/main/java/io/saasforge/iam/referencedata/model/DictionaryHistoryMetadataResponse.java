package io.saasforge.iam.referencedata.model;

import java.time.Instant;
import java.util.UUID;

/** 历史列表不携带完整覆盖快照。 */
public record DictionaryHistoryMetadataResponse(
        long version, String summary, UUID changedBy, String changedByName, Instant changedAt) {}
