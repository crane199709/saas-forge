package io.saasforge.iam.referencedata.model;

import java.time.Instant;
import java.util.UUID;

/** 历史列表不携带完整覆盖快照。 */
public record DictionaryHistoryMetadata(long version, String summary, UUID changedBy, Instant changedAt) {}
